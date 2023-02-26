package com.teaglu.dnsalias.processor.exception;

public class UpdateException extends Exception {
	private static final long serialVersionUID = 1L;

	public UpdateException(String message) {
		super(message);
	}
	public UpdateException(String message, Throwable cause) {
		super(message, cause);
	}
}
