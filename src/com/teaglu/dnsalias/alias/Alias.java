package com.teaglu.dnsalias.alias;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

public interface Alias {
	public @NonNull Iterable<@NonNull String> getSourceNames();
	public @Nullable Iterable<@NonNull String> getSourceServers();
	
	public @NonNull String getDestinationZone();
	public @NonNull String getDestinationName();
}
