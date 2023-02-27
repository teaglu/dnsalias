package com.teaglu.dnsalias.processor.dnsjava;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jdt.annotation.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;

import com.teaglu.dnsalias.alert.AlertCategory;
import com.teaglu.dnsalias.alert.AlertSink;
import com.teaglu.dnsalias.alias.Alias;
import com.teaglu.dnsalias.dns.DnsProvider;
import com.teaglu.dnsalias.dns.DnsRecord;
import com.teaglu.dnsalias.dns.DnsRecordType;
import com.teaglu.dnsalias.dns.DnsZone;
import com.teaglu.dnsalias.dns.exception.DnsException;
import com.teaglu.dnsalias.dns.record.ARecord;
import com.teaglu.dnsalias.processor.Processor;
import com.teaglu.dnsalias.processor.exception.LookupException;
import com.teaglu.dnsalias.processor.exception.UpdateException;

/**
 * DnsJavaProcessor
 * 
 * Implementation of Processor using the dnsjava library
 */
public class DnsJavaProcessor implements Processor {
	private static final Logger log= LoggerFactory.getLogger(DnsJavaProcessor.class);
	
	private @NonNull Alias alias;
	private @NonNull DnsProvider provider;
	
	// Inet4Address doesn't implement Comparable<Inet4Address> for some reason, so unless you
	// supply a comparator the set will blow up on the first operation.
	private static class Inet4Comparator implements Comparator<Inet4Address> {
		@Override
		public int compare(Inet4Address a, Inet4Address b) {
			byte[] aBytes= a.getAddress();
			byte[] bBytes= b.getAddress();
			
			for (int i= 0; i < aBytes.length; i++) {
				if (aBytes[i] < bBytes[i]) {
					return -1;
				} else if (aBytes[i] > bBytes[i]) {
					return 1;
				}
			}
			return 0;
		}
	}
	
	public String toString() {
		StringBuilder builder= new StringBuilder("alias([");
		boolean first= true;
		for (String name : alias.getSourceNames()) {
			if (first) {
				first= false;
			} else {
				builder.append(",");
			}
			builder.append(name);
		}
		builder.append("]->(");
		builder.append(alias.getDestinationName());
		builder.append(",");
		builder.append(alias.getDestinationZone());
		builder.append("))");
		
		return builder.toString();
	}
	
	private static final Inet4Comparator inet4Comparator= new Inet4Comparator();
	
	private Set<@NonNull Inet4Address> lastDestinations= null;
	
	private DnsJavaProcessor(@NonNull Alias alias, @NonNull DnsProvider provider) {
		this.alias= alias;
		this.provider= provider;
	}
	
	public static @NonNull Processor Create(@NonNull Alias alias, @NonNull DnsProvider provider) {
		return new DnsJavaProcessor(alias, provider);
	}
	
	private boolean compareSets(Set<@NonNull Inet4Address> a, Set<@NonNull Inet4Address> b) {
		for (Inet4Address member : a) {
			if (!b.contains(member)) {
				return false;
			}
		}
		
		for (Inet4Address member : b) {
			if (!a.contains(member)) {
				return false;
			}
		}
		
		return true;
	}
	
	private String setToString(Set<@NonNull Inet4Address> destinations) {
		StringBuilder destinationList= new StringBuilder();
		for (Inet4Address address : destinations) {
			if (!destinationList.isEmpty()) {
				destinationList.append(", ");
			}
			destinationList.append(address.getHostAddress());
		}
		
		return destinationList.toString();
	}
	
	@Override
	public long process(
			@NonNull AlertSink alertSink) throws LookupException, UpdateException
	{
		if (lastDestinations == null) {
			try {
				Set<@NonNull Inet4Address> destinations= new TreeSet<>(inet4Comparator);
				
				DnsZone zone= provider.getZone(alias.getDestinationZone());
				if (zone == null) {
					throw new UpdateException(
							"The destination zone could not be located by the update API");
				}

				Iterable<@NonNull DnsRecord> records=
						zone.findRecords(alias.getDestinationName(), DnsRecordType.A);
				
				for (DnsRecord record : records) {
					for (String value : record.getValues()) {
						InetAddress address= InetAddress.getByName(value);
						if (address instanceof Inet4Address) {
							destinations.add((Inet4Address)address);
						}
					}
				}
				
				lastDestinations= destinations;
				
				log.debug("Retrieved initial set of " + setToString(destinations));
			} catch (IOException e) {
				throw new UpdateException("IO Error retrieving DNS record", e);
			} catch (DnsException e) {
				throw new UpdateException("Error retrieving DNS record", e);
			}
		}
		
		Set<@NonNull Inet4Address> destinations= new TreeSet<>(inet4Comparator);
		long lowestTtl= 600;
		
		try {
			Iterable<@NonNull String> nameservers= alias.getSourceServers();
			
			for (String sourceName : alias.getSourceNames()) {
				// DnsJava requires the canonical dot at the end.
				String lookupName= sourceName;
				if (!lookupName.endsWith(".")) {
					lookupName= lookupName + ".";
				}
				
				Record queryRecord= Record.newRecord(
						Name.fromString(lookupName), Type.A, DClass.IN);

				// Null nameservers in the alias record means to use the system ones
				if (nameservers == null) {
					log.debug("Looking up " + sourceName + " using default nameservers");
					
					Message queryMessage= Message.newQuery(queryRecord);
					
					try {
						Resolver resolver= new SimpleResolver();
						
						Message queryResponse= resolver.send(queryMessage);
						
						List<Record> records= queryResponse.getSection(Section.ANSWER);
						for (Record record : records) {
							if (record.getType() == Type.A) {
								InetAddress address= InetAddress.getByName(record.rdataToString());
								if (address instanceof Inet4Address) {
									destinations.add((Inet4Address)address);
									
									long ttl= record.getTTL();
									if (ttl < lowestTtl) {
										lowestTtl= ttl;
									}
								}
							}
						}
					} catch (IOException lookupException) {
						throw new LookupException(
								"Unable to resolve [" + lookupName + "] with system resolver.",
								lookupException);
					}
				} else {
					boolean anySuccess= false;
					IOException lastException= null;
					
					Iterator<@NonNull String> nameserverIter= nameservers.iterator();
					while (nameserverIter.hasNext() && destinations.isEmpty()) {
						@SuppressWarnings("null")
						String nameserver= nameserverIter.next();
						
						log.debug("Looking up " + sourceName + " using nameserver " + nameserver);
						
						Message queryMessage= Message.newQuery(queryRecord);
						try {
							Resolver resolver= new SimpleResolver(nameserver);
							
							Message queryResponse= resolver.send(queryMessage);
							
							List<Record> records= queryResponse.getSection(Section.ANSWER);
							for (Record record : records) {
								if (record.getType() == Type.A) {
									InetAddress address= InetAddress.getByName(record.rdataToString());
									if (address instanceof Inet4Address) {
										destinations.add((Inet4Address)address);
										
										long ttl= record.getTTL();
										if (ttl < lowestTtl) {
											lowestTtl= ttl;
										}
									}
								}
							}
							
							anySuccess= true;
						} catch (IOException lookupException) {
							lastException= lookupException;
						}
					}
					
					if (!anySuccess) {
						throw new LookupException(
								"Unable to resolve [" + lookupName + "] with any listed " +
								"resolver.  The exception attached is the last.",
								lastException);
					}
				}
			}
		} catch (TextParseException e) {
			throw new LookupException("Error parsing text on DNS lookup", e);
		}
		
		boolean noChange= false;
		if (lastDestinations != null) {
			if (compareSets(lastDestinations, destinations)) {
				noChange= true;
			}
		}

		if (!noChange) {
			if (!destinations.isEmpty()) {
				log.debug("Targets: " + setToString(destinations));
				
				try {
					DnsZone zone= provider.getZone(alias.getDestinationZone());
					if (zone == null) {
						throw new UpdateException(
								"The destination zone could not be located by the update API");
					}
	
					zone.createRecord(ARecord.Create(
							alias.getDestinationName(), destinations, (int)lowestTtl),
							true);
				} catch (IOException e) {
					throw new UpdateException("IO Error updating DNS record", e);
				} catch (DnsException e) {
					throw new UpdateException("Error updating DNS record", e);
				}
			} else {
				try {
					DnsZone zone= provider.getZone(alias.getDestinationZone());
					if (zone == null) {
						throw new UpdateException(
								"The destination zone could not be located by the update API");
					}
	
					zone.deleteRecord(alias.getDestinationName(), DnsRecordType.A);
				} catch (IOException e) {
					throw new UpdateException("IO Error updating DNS record", e);
				} catch (DnsException e) {
					throw new UpdateException("Error updating DNS record", e);
				}
			}

			StringBuilder messageBuild= new StringBuilder();
			messageBuild.append("The DNS resolution for [");
			if (alias.getDestinationName().isBlank()) {
				messageBuild.append("@");
			} else {
				messageBuild.append(alias.getDestinationName());
			}
			messageBuild.append("] in zone [");
			messageBuild.append(alias.getDestinationZone());
			messageBuild.append("] has been updated from [");
			if (lastDestinations == null) {
				messageBuild.append("UNKNOWN");
			} else {
				messageBuild.append(setToString(lastDestinations));
			}
			messageBuild.append("] to [");
			messageBuild.append(setToString(destinations));
			messageBuild.append("]");
			
			@SuppressWarnings("null")
			@NonNull String message= messageBuild.toString();
			
			alertSink.sendAlert(destinations.isEmpty() ?
					AlertCategory.RESOLUTION_EMPTY : AlertCategory.RESOLUTION_CHANGE,
					message,
					null);
		}
		
		lastDestinations= destinations;
		
		return lowestTtl;
	}
}
