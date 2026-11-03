package com.ragagent.session.rag.plugin;



import com.ragagent.contract.event.Event;
import com.ragagent.contract.event.EventBusInterface;
import com.ragagent.contract.memory.MemoryItemView;
import com.ragagent.contract.pipeline.PipelinePorts;
import com.ragagent.contract.retrieval.RetrievalObsRows;
import com.ragagent.platform.event.EventType;
import com.ragagent.session.dto.MemoryRecalledData;
import com.ragagent.session.dto.PipelineUsedMemoryView;
import com.ragagent.session.rag.PipelineEventType;
import com.ragagent.session.rag.PipelineLog;
import com.ragagent.session.rag.PipelineState;
import com.ragagent.session.rag.support.MemoryUsedMemories;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MEMORY_RECALL 阶段插件：
 * 常驻块 + 情境条目注入本轮；无模型调用；记忆信封进 MemoryPrompt，
 * 结构化 UsedMemories 走 memory.usedMemoriesFromItems 投影并 emit memory_recalled 事件。
 */
public final class PluginMemoryRecall implements Plugin {

    private final PipelinePorts.MemoryService memoryService;

    public PluginMemoryRecall(PipelinePorts.MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public PipelineEventType[] activationEvents() {
        return new PipelineEventType[] {PipelineEventType.MEMORY_RECALL};
    }

    @Override
    public PluginError onEvent(PipelineEventType eventType, PipelineState chatManage, Plugin.Chain next) {
        if (memoryService == null) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("sessionId", chatManage.getSessionId());
            f.put("reason", "no_service");
            PipelineLog.info("MemoryRecall", "skip", f);
            return next.next();
        }

        Map<String, Object> in = new LinkedHashMap<>();
        in.put("sessionId", chatManage.getSessionId());
        in.put("query_len", chatManage.getQuery().length());
        in.put("query_preview", RetrievalObsRows.truncateRunes(chatManage.getQuery(), 200));
        PipelineLog.info("MemoryRecall", "input", in);

        var recall = memoryService.recall(chatManage.getQuery());
        String prompt = recall == null ? "" : recall.prompt();
        if (prompt.isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("sessionId", chatManage.getSessionId());
            f.put("items", 0);
            f.put("injected", false);
            f.put("note", "interest memories apply in query_understand, not here");
            PipelineLog.info("MemoryRecall", "output", f);
            return next.next();
        }

        chatManage.getQueryPlan().setMemoryPrompt(prompt);
        List<MemoryItemView> items = recall.items() == null ? List.of() : recall.items();
        List<PipelineUsedMemoryView> used = MemoryUsedMemories.usedMemoriesFromItems(items);
        chatManage.setUsedMemories(used);
        emitMemoryRecalled(chatManage.getRuntime().getEventBus(), chatManage.getSessionId(), used);

        List<String> memoryIDs = new ArrayList<>();
        for (MemoryItemView item : items) {
            if (item != null && item.getId() != null && !item.getId().isEmpty()) {
                memoryIDs.add(item.getId());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", chatManage.getSessionId());
        out.put("items", chatManage.getUsedMemories().size());
        out.put("injected", true);
        out.put("prompt_runes", PipelineLog.runeLength(prompt));
        out.put("memory_ids", memoryIDs);
        out.put("query_preview", RetrievalObsRows.truncateRunes(chatManage.getQuery(), 200));
        PipelineLog.info("MemoryRecall", "output", out);
        return next.next();
    }

    /**
     * 尽力而为的事件（emit 失败只告警不断流）。
     * EventMemoryRecalled 的 Data = MemoryRecalledData{Memories}。
     */
    static void emitMemoryRecalled(EventBusInterface bus, String sessionId,
                                   List<PipelineUsedMemoryView> used) {
        if (bus == null || used == null || used.isEmpty()) {
            return;
        }
        Event evt = new Event();
        evt.setType(EventType.EVENT_MEMORY_RECALLED);
        evt.setSessionId(sessionId);
        evt.setData(new MemoryRecalledData(used));
        try {
            bus.emit(evt);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("error", e.getMessage());
            PipelineLog.warn("MemoryRecall", "emit_failed", f);
        }
    }
}
