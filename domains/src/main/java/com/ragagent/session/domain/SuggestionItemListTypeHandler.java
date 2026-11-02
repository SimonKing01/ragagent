package com.ragagent.session.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.ragagent.contract.session.SuggestionItem;
import java.util.List;

/**
 * {@code message_suggestion_sets.questions} 列（{@code List<SuggestionItem>}）的处理器。
 *
 * <p>存在的理由见 {@code AbstractJsonListTypeHandler}：泛型擦除会导致元素类型丢失。</p>
 */
public class SuggestionItemListTypeHandler extends AbstractJsonListTypeHandler<SuggestionItem> {

    @Override
    protected TypeReference<List<SuggestionItem>> typeReference() {
        return new TypeReference<>() {};
    }
}
