package cn.yunshu.erp;

/** 供应商调用失败。区分可重试、永久失败和配置错误，避免无意义的重试。 */
class SupplierFailure extends RuntimeException {
    enum Kind { RETRYABLE, PERMANENT, CONFIG }
    private final Kind kind;
    private final String code;

    SupplierFailure(Kind kind,String code,String message) { this(kind,code,message,null); }
    SupplierFailure(Kind kind,String code,String message,Throwable cause) {
        super(message,cause);
        this.kind=kind;
        this.code=code;
    }
    Kind kind() { return kind; }
    String code() { return code; }
    static SupplierFailure retryable(String code,String message) { return new SupplierFailure(Kind.RETRYABLE,code,message); }
    static SupplierFailure permanent(String code,String message) { return new SupplierFailure(Kind.PERMANENT,code,message); }
    static SupplierFailure config(String code,String message) { return new SupplierFailure(Kind.CONFIG,code,message); }
}
