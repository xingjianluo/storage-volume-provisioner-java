package com.github.xingjianluo.storagevolumeprovisionerjava.tests.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单测日志辅助：统一打印【输入】【判定依据】【状态变化】，便于复盘。
 */
public final class TestLog {

    private static final Logger LOG = LoggerFactory.getLogger("TEST");

    private TestLog() {
    }

    public static void input(String title, Object detail) {
        LOG.info("[输入] {} => {}", title, detail);
    }

    public static void basis(String title, Object detail) {
        LOG.info("[判定依据] {} => {}", title, detail);
    }

    public static void state(String title, Object detail) {
        LOG.info("[状态变化] {} => {}", title, detail);
    }

    public static void assert_(String title, Object detail) {
        LOG.info("[断言] {} => {}", title, detail);
    }
}
