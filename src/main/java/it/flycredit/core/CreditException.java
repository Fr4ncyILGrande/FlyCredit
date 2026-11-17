package it.flycredit.core;

/** Keeps the error independent of the player while a database operation is running. */
public final class CreditException extends RuntimeException {
    private final String key;
    private final Object[] arguments;

    public CreditException(String key, Object... arguments) {
        super(Language.ENGLISH.text(key, arguments));
        this.key = key;
        this.arguments = arguments.clone();
    }

    public String key() { return key; }
    public Object[] arguments() { return arguments.clone(); }
}
