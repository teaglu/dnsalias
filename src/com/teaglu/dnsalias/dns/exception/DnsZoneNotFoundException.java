package com.teaglu.dnsalias.dns.exception;

public class DnsZoneNotFoundException extends DnsException {
	private static final long serialVersionUID = 1L;

	public DnsZoneNotFoundException(String message) {
		super(message);
	}

	public DnsZoneNotFoundException(String message, Throwable cause) {
		super(message, cause);
	}
}
