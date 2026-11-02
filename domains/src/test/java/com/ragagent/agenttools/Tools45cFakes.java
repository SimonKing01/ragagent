package com.ragagent.agenttools;



/**
 * 4.5c 测试的内存 fake（与录制脚本所用的同款 fake 形状一致）。
 */
public final class Tools45cFakes {

    private Tools45cFakes() {
    }

    /** 通用失败异常（fake 抛出，等价录制里的失败通道）。 */
    public static RuntimeException boom(String msg) {
        return new RuntimeException(msg);
    }

    /** 按 group+id 取录制常量（R_<GROUP>_<ID>，id 大写化）。 */
    public static com.fasterxml.jackson.databind.JsonNode rec45c(String group, String id) {
        String name = "R_" + group.toUpperCase().replace('-', '_')
                + "_" + id.toUpperCase().replace('-', '_');
        try {
            String json = (String) ToolContractGoldenC.class.getField(name).get(null);
            return ToolContractGoldenC.rec(json);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("unknown recording constant: " + name, e);
        }
    }

    /** 可变的 sandbox 文件源/汇（同时满足 Source/Sink/Editor 三种形状）。 */
}
