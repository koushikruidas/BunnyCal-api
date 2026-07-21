package io.bunnycal.hostpayments.provider;

public class HostPaymentProviderException extends RuntimeException {
    public HostPaymentProviderException(String operation, Throwable cause) {
        super("Host payment provider operation failed: " + operation, cause);
    }
}
