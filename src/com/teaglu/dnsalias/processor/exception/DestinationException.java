package com.teaglu.dnsalias.processor.exception;

public class DestinationException extends Exception {
	private static final long serialVersionUID = 1L;

	public DestinationException(String message) {
		super(message);
	}
	public DestinationException(String message, Throwable cause) {
		super(message, cause);
	}
}
