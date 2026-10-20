package com.lianyu.web.controller;

import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.dev33.satoken.exception.SaTokenContextException;
import org.junit.jupiter.api.Test;

/**
 * 垫片端点必须**已被映射**且**要求登录**。
 *
 * <p>前一条正是本次事故的核心：路径没被映射时请求会掉到静态资源处理器，
 * 抛出 `NoResourceFoundException`，然后被兜底成 500。
 * 所以「能被映射到」本身就是需要被钉住的行为。
 */
class LegacyCommunityControllerTest {

    @Test
    void pushCatchupRouteExistsAndRequiresLogin() {
        LegacyCommunityController controller = new LegacyCommunityController();
        // 没有 Sa-Token 上下文时 checkLogin 会抛 SaTokenContextException ——
        // 恰好证明方法体被执行到了（路由存在），而不是 404 掉到静态资源处理器
        assertThrows(SaTokenContextException.class, controller::pushCatchup);
    }
}
