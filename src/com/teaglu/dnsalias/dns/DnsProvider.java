package com.teaglu.dnsalias.dns;

import java.io.IOException;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.teaglu.dnsalias.dns.exception.DnsException;

/**
 * DnsProvider
 * 
 * Interface to a declared DNS provider entry, which includes any sort of authentication needed.
 * More than one of these may exist for a given type if they references for example two different
 * accounts using the same provider.
 */
public interface DnsProvider {
	/**
	 * getZone
	 * 
	 * @param apex						Zone name i.e. contoso.com
	 * @return							Reference to zone structure
	 * 
	 * @throws DnsException				Usually zone not found
	 * @throws IOException				Error talking to API
	 */
	public @Nullable DnsZone getZone(
			@NonNull String apex) throws DnsException, IOException;
}
