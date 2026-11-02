package com.ragagent.session.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.ragagent.contract.session.MentionedItem;
import java.util.List;

/**
 * {@code messages} 表上 {@code List<MentionedItem>} 型 jsonb 列的处理器。
 *
 * <p>存在的理由见 {@link AbstractJsonListTypeHandler}：泛型擦除会导致元素类型丢失。</p>
 */
public class MentionedItemListTypeHandler extends AbstractJsonListTypeHandler<MentionedItem> {

    @Override
    protected TypeReference<List<MentionedItem>> typeReference() {
        return new TypeReference<>() {};
    }
}
