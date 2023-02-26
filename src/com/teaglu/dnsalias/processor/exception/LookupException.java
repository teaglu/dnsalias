package com.teaglu.dnsalias.processor.exception;

public class LookupException extends Exception {
	private static final long serialVersionUID = 1L;

	public LookupException(String message, Throwable cause) {
		super(message, cause);
	}
}
