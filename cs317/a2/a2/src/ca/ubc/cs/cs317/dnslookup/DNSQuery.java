package ca.ubc.cs.cs317.dnslookup;

import java.net.InetAddress;

public class DNSQuery {

    private int queryID;
    private String domainName;
    private InetAddress ipAddress;
    private RecordType recordType;
    private byte[] dnsRequest;

    public DNSQuery(int queryID, String domainName, InetAddress ipAddress, RecordType recordType, byte[] dnsRequest) {
        this.queryID = queryID;
        this.domainName = domainName;
        this.ipAddress = ipAddress;
        this.recordType = recordType;
        this.dnsRequest = dnsRequest;
    }

    public int getQueryID() {
        return queryID;
    }

    public String getDomainName() {
        return domainName;
    }

    public InetAddress getIpAddress() {
        return ipAddress;
    }

    public RecordType getRecordType() {
        return recordType;
    }

    public byte[] getDnsRequest() {
        return dnsRequest;
    }
}