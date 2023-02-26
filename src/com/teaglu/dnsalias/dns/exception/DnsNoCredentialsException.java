package com.teaglu.dnsalias.dns.exception;

public class DnsNoCredentialsException extends DnsException {
	private static final long serialVersionUID = 1L;

	public DnsNoCredentialsException(String message) {
		super(message);
	}

	public DnsNoCredentialsException(String message, Throwable cause) {
		super(message, cause);
	}
}
