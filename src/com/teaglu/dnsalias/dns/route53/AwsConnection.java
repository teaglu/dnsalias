package com.teaglu.dnsalias.dns.route53;

import org.eclipse.jdt.annotation.NonNull;

import software.amazon.awssdk.services.route53.Route53Client;

/**
 * AwsConnection
 *
 * This class represents a connection to AWS with given credentials and role usage.
 *
 */
public interface AwsConnection {
	/**
	 * getDescription
	 * 
	 * Get a printable description for this connection
	 * 
	 * @return							Description
	 */
	public @NonNull String getDescription();
	
	/**
	 * buildRoute53Client
	 * 
	 * Build a new Route53 client
	 * 
	 * @return							Route53 API client
	 */
	public @NonNull Route53Client buildRoute53Client();
}