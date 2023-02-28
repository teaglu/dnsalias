package com.teaglu.dnsalias.alias;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Alias
 *
 * Interface for representing an alias
 */
public interface Alias {
	/**
	 * getSourceNames
	 * 
	 * Get the source names - i.e. the full DNS names we're lookup up.
	 * 
	 * @return							Iterable of names
	 */
	public @NonNull Iterable<@NonNull String> getSourceNames();
	
	/**
	 * getSourceServers
	 * 
	 * Get the source DNS servers, or null if the system servers should be used.
	 * 
	 * @return							Iterable of servers or null
	 */
	public @Nullable Iterable<@NonNull String> getSourceServers();

	/**
	 * getDestinationZone
	 * 
	 * Get the destination zone to update.  This can't be assumed to just be
	 * the part after the first dot, because we could want to set:
	 * 
	 * - Set the entry for portal.intranet in the zone contoso.com
	 * - Set the entry for portal in the zone intranet.contoso.com
	 * 
	 * These are two different things.
	 * 
	 * @return							Destination zone.
	 */
	public @NonNull String getDestinationZone();
	
	/**
	 * getDestinationName
	 * 
	 * Return the name to update, relative to the destination zone.
	 * 
	 * @return							Destination name.
	 */
	public @NonNull String getDestinationName();
}
