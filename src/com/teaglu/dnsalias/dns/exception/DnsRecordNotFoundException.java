package com.teaglu.dnsalias.dns.exception;

public class DnsRecordNotFoundException extends DnsException {
	private static final long serialVersionUID = 1L;

	public DnsRecordNotFoundException(String message) {
		super(message);
	}

	public DnsRecordNotFoundException(String message, Throwable cause) {
		super(message, cause);
	}
}
