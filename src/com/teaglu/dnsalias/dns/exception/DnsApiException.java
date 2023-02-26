package com.teaglu.dnsalias.dns.exception;

public class DnsApiException extends DnsException {
	private static final long serialVersionUID = 1L;

	public DnsApiException(String message) {
		super(message);
	}

	public DnsApiException(String message, Throwable cause) {
		super(message, cause);
	}
}
