package com.ragagent.contract.agent;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 技能目录端口（业务域 → agent 引擎； 契约化）。
 *
 * <p>管理面（两个控制器）与会话装配原先直注 {@code agent.skills.SkillCatalogService} 实现类；
 * 本端口收域侧真调用的六个方法 + 两个形状 + 五个异常（原嵌在实现类与 {@code Skill} 内，
 * 随之迁到契约面）。实现（{@code SkillCatalogService}）语义不变：租户作用域校验、
 * 平台内置层只读、被引用保护等都在实现里。</p>
 *
 * <p>{@code SKILL_FILE_NAME} 原为 {@code SkillCatalogPort.SKILL_FILE_NAME}（技能的固定文件名），
 * 随端口上提以免域侧再依赖 Skill 实现类。</p>
 */
public interface SkillCatalogPort {

    /** 技能文件固定名（原 {@code SkillCatalogPort.SKILL_FILE_NAME}）。 */
    String SKILL_FILE_NAME = "SKILL.md";

public record SkillRow(
        String id,
        Long tenantId,
        String slug,
        String name,
        String description,
        String content,
        Integer version,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** 平台内置层：全员可见、只读。 */
    public boolean platform() {
        return tenantId == null;
    }
}
public record SkillReference(String agentId, String agentName, boolean allMode) {
}
public static final class DuplicateSlugException extends RuntimeException {
    public DuplicateSlugException(String slug) {
        super("skill slug already exists in this workspace: " + slug);
    }
}
public static final class SkillNameConflictException extends RuntimeException {
    public SkillNameConflictException(String name, boolean platformHit) {
        super(platformHit
                ? "skill name is taken by a platform builtin: " + name
                : "skill name already exists in this workspace: " + name);
    }
}
public static final class PlatformSkillReadOnlyException extends RuntimeException {
    public PlatformSkillReadOnlyException() {
        super("platform builtin skills are read-only for workspace admins");
    }
}
public static final class RenameWhileReferencedException extends RuntimeException {

    private final transient List<SkillReference> references;

    public RenameWhileReferencedException(List<SkillReference> references) {
        super("skill is referenced by " + (references == null ? 0 : references.size())
                + " agent(s); rename would orphan them");
        this.references = references == null ? List.of() : List.copyOf(references);
    }

    public List<SkillReference> references() {
        return references;
    }
}
public static final class SkillValidationException extends RuntimeException {
    public SkillValidationException(String message) {
        super(message);
    }

    public SkillValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
    List<SkillRow> listVisible(Long tenantId);

    Optional<SkillRow> findVisibleById(String id, Long tenantId);

    SkillRow create(String slug, String name, String description, String body,
                    String createdBy, Long tenantId);

    SkillRow update(SkillRow current, String name, String description, String body, Long tenantId);

    boolean softDelete(SkillRow current, Long tenantId);

    List<SkillReference> listReferences(String slug, String name, Long tenantId);

    /**
     * 从落库的 SKILL.md 文本还原正文（instructions）。
     *
     * <p>原域侧直接调 {@code Skill.parseSkillFile(content).instructions}（组装与还原严格可逆：
     * 两侧都 strip）；本方法把该能力收进端口，域侧不再认识 Skill 实现类。</p>
     */
    String skillInstructions(String skillFileContent);
}
