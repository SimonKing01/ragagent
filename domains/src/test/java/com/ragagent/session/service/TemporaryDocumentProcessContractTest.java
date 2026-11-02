package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.contract.doc.DocReaderParsePort;
import com.ragagent.contract.knowledge.DocumentChunkingPort;
import com.ragagent.contract.model.AsrTranscriber;
import com.ragagent.contract.model.ModelCatalogPort.ModelRowView;
import com.ragagent.contract.model.ModelParameters;
import com.ragagent.contract.model.ModelRuntime;
import com.ragagent.contract.tenant.TenantConfigLookup;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.session.attachment.AttachmentFileStore;
import com.ragagent.session.attachment.TemporaryDocumentService;
import com.ragagent.session.domain.document.TemporaryDocument;
import com.ragagent.session.repository.TemporaryDocumentRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 附件解析链路的验收（资源租户、引擎回落与音频分支）：
 * {@code resource_tenant_id} 决定解析依赖范围、租户级 chat 规则兜底 parser engine、
 * 音频走 ASR（无模型 → "audio transcription model is not configured"）。
 */
class TemporaryDocumentProcessContractTest {

    private static final long TENANT = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TemporaryDocumentRepository repo;
    private AttachmentFileStore fileStore;
    private DocReaderParsePort docReader;
    private ModelRuntime modelRuntimeAssembler;
    private AsrTranscriber asrTranscriber;
    private TenantConfigLookup tenantService;
    private TemporaryDocumentService service;

    @BeforeEach
    void setUp() {
        repo = mock(TemporaryDocumentRepository.class);
        fileStore = mock(AttachmentFileStore.class);
        docReader = mock(DocReaderParsePort.class);
        modelRuntimeAssembler = mock(ModelRuntime.class);
        asrTranscriber = mock(AsrTranscriber.class);
        tenantService = mock(TenantConfigLookup.class);
        service = new TemporaryDocumentService(repo, fileStore, docReader,
                modelRuntimeAssembler, asrTranscriber, tenantService,
chunkingStub());
        TenantContext.set(TENANT, null, null, false, "u-1", false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static TemporaryDocument document(String fileName, String fileType,
            String optionsJson) {
        TemporaryDocument d = new TemporaryDocument();
        d.setId("d-1");
        d.setTenantId(TENANT);
        d.setSessionId("s-1");
        d.setResourceRef("local://1/exports/d-1");
        d.setFileName(fileName);
        d.setFileType(fileType);
        d.setStatus(TemporaryDocument.STATUS_UPLOADED);
        d.setProcessingOptions(optionsJson);
        return d;
    }

    private static ModelRowView asrModel() {
        return new ModelRowView("asr-1", "asr-test", "KnowledgeQA", "remote", "active", false,
                new ModelParameters());
    }

    /** 租户的 chat 解析规则节点（pdf → engine）；自 A③ 起经 L1 端口读配置，不再取实体。 */
    private static ObjectNode parserEngineConfigWithRule(String ext, String engine) {
        ObjectNode cfg = MAPPER.createObjectNode();
        ArrayNode rules = cfg.putArray("chatParserEngineRules");
        ObjectNode rule = rules.addObject();
        rule.putArray("file_types").add(ext);
        rule.put("engine", engine);
        return cfg;
    }

    @Test
    void audioIsTranscribedWhenAsrConfigured() {
        when(repo.getById(TENANT, "d-1")).thenReturn(document("voice.mp3", ".mp3",
                "{\"asrModelId\":\"asr-1\",\"resourceTenantId\":42}"));
        when(fileStore.getFile("local://1/exports/d-1")).thenReturn(new byte[] {1, 2, 3});
        when(modelRuntimeAssembler.getAsrModel("asr-1")).thenReturn(asrModel());
        when(asrTranscriber.transcribe(any(), any(), any()))
                .thenReturn(new AsrTranscriber.TranscriptionResult("转写文本", null));

        service.processNow(TENANT, "d-1");

        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(repo).markReady(eq(TENANT), eq("d-1"), eq("转写文本"), anyString(), eq("null"),
                metadata.capture(), anyInt(), anyInt(), any());
        assertThat(metadata.getValue()).contains("\"parser\":\"asr\"");
        // withResourceTenant 在调用后恢复原租户
        assertThat(TenantContext.currentTenantId()).isEqualTo(TENANT);
    }

    @Test
    void audioWithoutAsrModelFails() {
        when(repo.getById(TENANT, "d-1")).thenReturn(document("voice.mp3", ".mp3", "{}"));
        when(fileStore.getFile("local://1/exports/d-1")).thenReturn(new byte[] {1});

        service.processNow(TENANT, "d-1");

        verify(repo).markFailed(TENANT, "d-1", "audio transcription model is not configured");
    }

    @Test
    void tenantRuleFillsEngineWhenOptionsLackOne() throws Exception {
        when(repo.getById(TENANT, "d-1")).thenReturn(document("report.pdf", ".pdf", "{}"));
        when(fileStore.getFile("local://1/exports/d-1")).thenReturn(new byte[] {1});
        when(tenantService.parserEngineConfig(TENANT)).thenReturn(parserEngineConfigWithRule("pdf", "mineru"));
        when(docReader.parse(any(), anyString(), anyString(), anyString(), eq("mineru")))
                .thenReturn(new DocReaderParsePort.ParsedDocumentView("# md", 0, List.of()));

        service.processNow(TENANT, "d-1");

        verify(docReader).parse(any(), eq("report.pdf"), eq("pdf"), eq("report.pdf"),
                eq("mineru"));
    }

    @Test
    void resourceTenantDrivesEngineFallback() throws Exception {
        when(repo.getById(TENANT, "d-1")).thenReturn(document("report.pdf", ".pdf",
                "{\"resourceTenantId\":42}"));
        when(fileStore.getFile("local://1/exports/d-1")).thenReturn(new byte[] {1});
        when(tenantService.parserEngineConfig(42L))
                .thenReturn(parserEngineConfigWithRule("pdf", "paddleocr_vl"));
        when(docReader.parse(any(), anyString(), anyString(), anyString(), eq("paddleocr_vl")))
                .thenReturn(new DocReaderParsePort.ParsedDocumentView("# md", 0, List.of()));

        service.processNow(TENANT, "d-1");

        verify(docReader).parse(any(), eq("report.pdf"), eq("pdf"), eq("report.pdf"),
                eq("paddleocr_vl"));
    }

    @Test
    void explicitEngineSkipsTenantFallback() throws Exception {
        when(repo.getById(TENANT, "d-1")).thenReturn(document("report.pdf", ".pdf",
                "{\"parserEngine\":\"simple\"}"));
        when(fileStore.getFile("local://1/exports/d-1")).thenReturn(new byte[] {1});
        when(docReader.parse(any(), anyString(), anyString(), anyString(), eq("simple")))
                .thenReturn(new DocReaderParsePort.ParsedDocumentView("# md", 0, List.of()));

        service.processNow(TENANT, "d-1");

        verify(docReader).parse(any(), eq("report.pdf"), eq("pdf"), eq("report.pdf"),
                eq("simple"));
    }

    /** 分块桩：委托**真实**域内实现（保持测试对 chunks JSON 的语义断言）。 */
    private static DocumentChunkingPort chunkingStub() {
        return (content, cfg) -> {
            com.ragagent.knowledge.chunker.SplitterConfig sc = new com.ragagent.knowledge.chunker.SplitterConfig();
            sc.setChunkSize(cfg.chunkSize());
            sc.setChunkOverlap(cfg.chunkOverlap());
            sc.setStrategy(cfg.strategy());
            return com.ragagent.knowledge.chunker.Chunker.split(content, sc).stream()
                    .map(x -> new DocumentChunkingPort.ChunkedPart(x.getContent(), x.getContextHeader(),
                            x.embeddingContent(), x.getSeq(), x.getStart(), x.getEnd()))
                    .toList();
        };
    }
}
