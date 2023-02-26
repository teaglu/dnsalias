package com.teaglu.dnsalias.util;

import java.util.Iterator;

import org.eclipse.jdt.annotation.NonNull;

public class IterableOfOne<T> implements Iterable<@NonNull T> {
	private @NonNull T value;
	
	public IterableOfOne(@NonNull T value) {
		this.value= value;
	}
	
	private class Instance implements Iterator<@NonNull T> {
		public boolean used;
		
		@Override
		public boolean hasNext() {
			return !used;
		}

		@Override
		public @NonNull T next() {
			used= true;
			return value;
		}
		
	}
	
	@Override
	public Iterator<@NonNull T> iterator() {
		return new Instance();
	}
}
