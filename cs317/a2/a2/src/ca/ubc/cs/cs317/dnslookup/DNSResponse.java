package ca.ubc.cs.cs317.dnslookup;

import java.util.ArrayList;
import java.util.List;

public class DNSResponse {

    private int queryID;
    private boolean authoritative;
    private int rCode;
    private List<ResourceRecord> answerRecords = new ArrayList<>();
    private List<ResourceRecord> authorityRecords = new ArrayList<>();
    private List<ResourceRecord> additionalRecords = new ArrayList<>();

    public DNSResponse(int queryID, boolean authoritative, int rCode) {
        this.queryID = queryID;
        this.authoritative = authoritative;
        this.rCode = rCode;
    }

    public void addAnswerRecord(ResourceRecord answer) {
        answerRecords.add(answer);
    }

    public void addAuthorityRecord(ResourceRecord authority) {
        authorityRecords.add(authority);
    }   
    
    public void addAdditionalRecord(ResourceRecord additional) {
        additionalRecords.add(additional);
    }

    public int getQueryID() {
        return queryID;
    }

    public boolean getAuthoritative() {
        return authoritative;
    }

    public int getRCode() {
        return rCode;
    }

    public List<ResourceRecord> getAnswerRecords() {
        return answerRecords;
    }    
    
    public List<ResourceRecord> getAuthorityRecords() {
        return authorityRecords;
    }   
    
    public List<ResourceRecord> getAdditionalRecords() {
        return additionalRecords;
    }
}