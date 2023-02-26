# DNS Alias

## Purpose

This project monitors one or more DNS records, resolves them to IP addresses, and if the list of
records has changed, it uses the API of a DNS hosting provider to update another record to match.

The polling period is based on the minimum TTL returned from the source, with an enforced minimum
scheduling delay of 100 milliseconds.  The end effect is logically equivalent to a CNAME record,
but without delegating the entire domain.

## Primary Use Case

Marketing companies often want to host their work product at the zone apex, for example
`consoso.com`, instead of on a sub-domain such as `www.contoso.com`.  If the URL hosting
the site were anywhere but the zone apex a CNAME would suffice, but CNAMEs cannot be used at a
zone apex without re-directing the entire domain.  This package allows IT departments to maintain
operational control of their production DNS zones, while still meeting the needs of the marketing
company.

In the case of an endpoint such as an AWS Application Load Balancer where a static IP is not
available, the package can be configured to monitor the intermediate name for changes and
replicate these changes to an apex A record in the target domain.

In cases where a hosting company will only provide DNS server addresses, the package can be
configured to poll the company's DNS servers directly by IP, and replicate any changes to the
production DNS infrastructure.

## Supported Providers

Currently only Route53 and Cloudflare are supported as DNS providers.

## Configuration File

This product is dynamically configurable using the "configure" library.  Configuration examples
are shown in JSON format, but the product can also be configured via YAML.

### Provider Section

The providers section of the configuration file consists of a set of named DNS providers.  Each
entry must have a string property named "type" which determines the type of provider.

    "providers": {
        "myaccount": {
            "type": "route53",
            "region": "us-east-1"
        }
    }

#### AWS Provider

The AWS provider has optional keys depending on the type of access.  If an `accessKey` and
`secretKey` are provided, then initial access will be based on the access and secret key.
If these properties are not specified, then access will be be based on the intrinsic execution
role.

If the key `assumeRole` is present, then after credentials are established the named role will
be assumed using the STS API.

#### CloudFlare Provider

FIXME

### Aliases Section

The aliases section of the configuration file consists of a named set of DNS aliases.  Each
alias has three component keys:  a `source` object describing the DNS records which is monitored,
a `destination` object which describes the DNS record to be created, and a `provider`
key which references the provider section to be used for updates.

#### Source Section

The source property is an object.

If the string property `name` is defined it is used as a single
name to be monitored, or the array of strings property `names` can be used to specify
more than on name to be monitored.

If the string property `server` is defined it is used as the name of a single DNS server
to be used for retrieval.  If the array of strings property `servers` is defined, it is used
as a list of DNS servers to be used for retrieval.  If more than one DNS server is defined for a
source, then a request will only be considered failed if all defined DNS servers have failed to
respond.  After any DNS server responds no other servers will be referenced.

#### Destination Section

The destination property is an object

The string property `name` sets the DNS name which should be updated.  If the string property
`zone` is present, then the `zone` property determines the name of the zone which should be
updated, and the `name` property should be set to the relative name to be updated.

If the string property `zone` is not present, then the product will assume that the zone to
be updated is the one immediately containing the name, and the name will be interpreted as the
single segment to be updated.

