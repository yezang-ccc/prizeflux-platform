package com.prizeflux.campaign.application;

/** 令牌缺失、过期、篡改或声明不合法时的统一失败；公开响应不区分具体原因。 */
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException() {
        super("Invalid token");
    }
}
