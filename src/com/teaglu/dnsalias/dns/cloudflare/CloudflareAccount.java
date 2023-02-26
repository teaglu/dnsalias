package com.teaglu.dnsalias.dns.cloudflare;

import java.io.IOException;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonObject;
import com.teaglu.composite.Composite;
import com.teaglu.dnsalias.dns.exception.DnsApiException;

public interface CloudflareAccount {
	public @NonNull Composite create(
			@NonNull String relativeUrl,
			@NonNull JsonObject body) throws IOException, DnsApiException;

	public @NonNull	Iterable<@NonNull Composite> query(
			@NonNull String relativeUrl,
			@Nullable Map<@NonNull String,
			@Nullable String> parameters) throws IOException, DnsApiException;
	
	public @NonNull	Composite update(
			@NonNull String relativeUrl,
			@NonNull JsonObject body) throws IOException, DnsApiException;

	public void delete(
			@NonNull String relativeUrl) throws IOException, DnsApiException;
}
