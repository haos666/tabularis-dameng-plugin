package dev.tabularis.dameng;

final class RpcException extends RuntimeException {
    private final int code;

    RpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    RpcException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    int code() {
        return code;
    }
}
