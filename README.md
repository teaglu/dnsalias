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

The following example provider sections defines one provider named `client1` that accesses AWS
using an access key / secret key combination, then assumes a role to access another account:

    "providers": {
        "client1": {
            "type": "route53",
            "accessKey": "234lkjlkjasdf",
            "secretKey": "a23lk4jlasdkjf2lkqj345lkqj2345lkj",
            "assumeRole": "arn:aws:iam::123456789012:role/client-admin"
        }
    }

#### CloudFlare Provider

The Cloudflare provider has a single key `apiToken` that should match an API token generated
from the Cloudflare management console.

The following example provider section defines one provider named `client2` that accesses
a Cloudflare account:

    "providers": {
        "client2": {
            "type": "cloudflare",
            "apiToken": "asldkjfwlkjarlkeja"
        }
    }

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

## Example Full Configuration File in JSON

The example configuration is for an example domain hosted in Digital Ocean, but where the DNS
records are controlled by IT operations in an AWS account using Route53.  The job monitors the A
addresses reported by the Digigal Ocean nameservers, and replicates the apex record to the
apex of the Route53 zone.

Since the job is running as a container in ECS, it can be given a role that has the necessary
permissions to update DNS records.

    {
        "alerts": {
            "type": "smtp",
            "host": "smtp.contoso.com",
            "username": "dnsalias",
            "password": "hunter2",
            "from": "DNS Alias Job <dnsalias@contoso.com>",
            "to": [
                "Super User <suser@contoso.com>"
            ]
        },
        "providers": {
            "client1": {
                "type": "route53",
                "region": "us-east-1"
            }
        },
        "aliases": {
            "client1-apex": {
                "source": {
                    "name": "contoso.com",
                    "servers": [
                        "ns1.digitalocean.com",
                        "ns2.digitalocean.com"
                    ]
                },
                "destination": {
                    "name": "",
                    "zone": "contoso.com"
                },
                "provider": "client1"
            }
        }
    }

## Running the Program

You pass the configuration source using the environment variables CONFIGURATION and SECRETS -
the format of these is described in the [configure](https://github.com/teaglu/configure) page.  A
basic string to point to a file is `file://{path}`.  For example, the following string would
work on a Linux system:

    file://etc/dnsalias/config.json
    
While the following string might work on a Windows system:

    file://C/Users/Rando/Desktop/dnsalias.json

Any environment with a Java 11 or higher runtime can run the application from the command line
with the following command:

    java -jar dnsalias.jar
    
You may want to included additional arguments to limit the amount of memory used or otherwise
tune the environment.

## Running as an AWS Lambda

The program can be used as an AWS Lambda, typically triggered by a periodic EventBridge schedule.
This solution will not respond as quickly as running the program continuously, since the built-in
scheduler is designed to track the TTL of the source records.  It can however be useful to
take advantage of the generous Lambda free tier.

When used as an AWS Lambda function, the program will read the configuration from its input in
JSON format, evaluate each alias exactly once, and return.  This allows you to create one Lambda
function containing the program, and execute it from different EventBridge schedules to handle
different tasks or clients.

To use the program as a Lambda function:

* Use the Java 11 (Corretto) runtime.
* Choose either x86_64 or arm64 platform.
* Specify `com.teaglu.dnsalias.Lambda` as the Handler.
* Increase the execution timeout to 60 seconds or higher.

The default execution timeout of 15 seconds may expire if the program fails to contact the first
DNS server and has to move down the list.  Multiple aliases are evaluated in parallel, but a
single alias is not.

The SECRETS environment variable may still be specified to pass credentials separately, for
example to reference AWS SecretsManager.

