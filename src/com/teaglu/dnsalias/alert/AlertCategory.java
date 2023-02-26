package com.teaglu.dnsalias.alert;

public enum AlertCategory {
	// An exception occurred during configuration
	CONFIGURATION_EXCEPTION,
	
	// An exception occurred retrieving the source values
	LOOKUP_EXCEPTION,

	// An exception occurred updating the DNS provider
	UPDATE_EXCEPTION,
	
	// An uncaught exception occurred during processing
	PROCESSING_EXCEPTION,
	
	// The resolved IP address changed from the last known value
	RESOLUTION_CHANGE,
	
	// The source name resolved to nothing / NXDOMAIN
	RESOLUTION_EMPTY
}
