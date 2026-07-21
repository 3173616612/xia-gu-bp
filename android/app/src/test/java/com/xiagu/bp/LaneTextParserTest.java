package com.xiagu.bp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class LaneTextParserTest {
    @Test
    public void readsLanePromptWithoutUsingHeroNames() {
        assertEquals("发育路", LaneTextParser.detect("请选择您的发育路英雄 18"));
        assertEquals("游走", LaneTextParser.detect("请选择您的辅助英雄"));
        assertNull(LaneTextParser.detect("孙权 虞姬 艾琳 敖隐 后羿"));
    }
}
