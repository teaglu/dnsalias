package com.teaglu.dnsalias.dns.exception;

public class DnsException extends Exception {
	private static final long serialVersionUID = 1L;

	public DnsException(String message) {
		super(message);
	}
	
	public DnsException(String message, Throwable cause) {
		super(message, cause);
	}
}
