package com.lianyu.service.sticker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.CharacterSticker;
import com.lianyu.dao.mapper.CharacterStickerMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 角色表情包候选（只读）。
 *
 * <p>这里只回答「这个角色有哪些图」，**不**回答「这一轮该不该发、发哪张」——
 * 后者要看回复文本的情绪，属于模型侧判断（Python 的 {@code app/stickers.py}）。
 * 拆成两半的理由与记忆域一致：图是事实数据（Java + MySQL），
 * 挑选是 agent 的判断，任一边独占都会让另一边被迫长 AI 逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StickerService {

    /** 一次回合最多带这么多候选：再多也进不了模型预算，反而干扰挑选。 */
    private static final int MAX_CANDIDATES = 20;

    private final CharacterStickerMapper characterStickerMapper;

    /**
     * 回合用的候选列表（按 sort_order 排好，顺序稳定 → Python 侧同分时结果可复现）。
     * 没有公网 URL 的行直接跳过：QQ 侧只能按 URL 取图。
     */
    public List<Map<String, String>> candidatesFor(Long characterId) {
        if (characterId == null) {
            return List.of();
        }
        List<CharacterSticker> rows = characterStickerMapper.selectList(
                new LambdaQueryWrapper<CharacterSticker>()
                        .eq(CharacterSticker::getCharacterId, characterId)
                        .eq(CharacterSticker::getEnabled, 1)
                        .orderByAsc(CharacterSticker::getSortOrder)
                        .orderByAsc(CharacterSticker::getId)
                        .last("LIMIT " + MAX_CANDIDATES));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> candidates = new ArrayList<>(rows.size());
        for (CharacterSticker row : rows) {
            String url = row.getPublicUrl();
            if (url == null || url.isBlank()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>(3);
            item.put("url", url.trim());
            item.put("emotion", row.getEmotion() == null ? "" : row.getEmotion().trim());
            item.put("tags", row.getTagsJson() == null ? "" : String.join(",", row.getTagsJson()));
            candidates.add(item);
        }
        return candidates;
    }
}
