package com.teaglu.dnsalias.alias.impl;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.MissingValueException;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.dnsalias.alias.Alias;

public class CompositeAlias implements Alias {
	private final @NonNull List<@NonNull String> sourceNames= new ArrayList<>(5);
	private List<@NonNull String> sourceServers;
	
	private final @NonNull String destinationZone;
	private final @NonNull String destinationName;
	
	private CompositeAlias(@NonNull Composite config) throws SchemaException {
		{
			Composite source= config.getRequiredObject("source");
			
			String singleName= source.getOptionalString("name");
			if (singleName != null) {
				sourceNames.add(singleName);
			} else {
				Iterable<@NonNull String> names= source.getRequiredStringArray("names");
				for (String name : names) {
					sourceNames.add(name);
				}
			}
			
			String singleServer= source.getOptionalString("server");
			if (singleServer != null) {
				sourceServers= new ArrayList<>(5);
				sourceServers.add(singleServer);
			} else {
				Iterable<@NonNull String> servers= source.getOptionalStringArray("servers");
				if (servers != null) {
					sourceServers= new ArrayList<>(5);
					for (String server : servers) {
						sourceServers.add(server);
					}
				}
			}
		}
		
		{
			Composite destination= config.getRequiredObject("destination");
			
			String name= destination.getRequiredString("name");
			String zone= destination.getOptionalString("zone");
			if (zone == null) {
				int offset= name.indexOf('.');
				if (offset == -1) {
					throw new MissingValueException("destination.zone");
				}
				
				zone= name.substring(offset + 1);
				if (zone == null) {
					throw new RuntimeException("Null return from substr");
				}
				name= name.substring(0, offset);
				if (name == null) {
					throw new RuntimeException("Null return from substr");
				}
			}
			
			destinationName= name;
			destinationZone= zone;
		}
	}
	
	public static @NonNull Alias Create(
			@NonNull Composite config) throws SchemaException
	{
		return new CompositeAlias(config);
	}

	@Override
	public @NonNull Iterable<@NonNull String> getSourceNames() {
		return sourceNames;
	}

	@Override
	public @Nullable Iterable<@NonNull String> getSourceServers() {
		return sourceServers;
	}

	@Override
	public @NonNull String getDestinationZone() {
		return destinationZone;
	}

	@Override
	public @NonNull String getDestinationName() {
		return destinationName;
	}
}
