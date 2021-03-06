package com.tayo.KinesisEncryption;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.amazonaws.encryptionsdk.AwsCrypto;
import com.amazonaws.encryptionsdk.kms.KmsMasterKeyProvider;
import com.amazonaws.services.kinesis.model.*;
import junit.framework.Assert;
import kinesisencryption.dao.BootCarObject;
import kinesisencryption.utils.KinesisEncryptionUtils;


import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesis.AmazonKinesisClient;
import com.amazonaws.services.kms.AWSKMSClient;
import com.amazonaws.services.kms.model.DecryptRequest;
import com.amazonaws.services.kms.model.DecryptResult;

import junit.framework.TestCase;

public class TestKinesisEncryptionUtils extends TestCase
{
	BootCarObject car;
	String keyId;
	AmazonKinesisClient kinesis;
	private static final String STREAM_NAME = "UnitTestStream";
	AWSKMSClient kms;
	private final static CharsetDecoder decoder = Charset.forName("UTF-8").newDecoder();
	final static String keyArn = "arn:aws:kms:us-east-1:xxxxxxx:key/mykey-3f1c-4a77-a51d-isinaws";
	final AwsCrypto crypto = new AwsCrypto();
	static final String filePath = "/Users/my_name/workspace/kinesisencryption/script/kplWatch/2177dff9-330c-46b7-801f-e1ed3ba1e8e0.json";
	final Map<String, String> context = Collections.singletonMap("Kinesis", "encryptionContext");
	final KmsMasterKeyProvider prov = new KmsMasterKeyProvider(keyArn);


	public void setUp() throws Exception
	{
		super.setUp();
		car = new BootCarObject("Volvo 740 GL", "134000", "2012");
		keyId="mykey-3f1c-4a77-a51d-isinaws";
		kinesis = new AmazonKinesisClient(new DefaultAWSCredentialsProviderChain()
    			.getCredentials()).withRegion(Regions.US_EAST_1);
		TestKinesisEncryptionUtils.createStream(kinesis);
		kms = new AWSKMSClient(new DefaultAWSCredentialsProviderChain()
    			.getCredentials()).withRegion(Regions.US_EAST_1);

	}
	
	public void tearDown() throws Exception
	{
		DeleteStreamRequest delete = new DeleteStreamRequest();
		delete.setStreamName(STREAM_NAME);
		DeleteStreamResult result = kinesis.deleteStream(delete);
		super.tearDown();
			
	}


	

	public void testToByteStream() throws UnsupportedEncodingException
	{		
		ByteBuffer encryptedData = KinesisEncryptionUtils.toEncryptedByteStream(kms, car, keyId);
		PutRecordRequest putRecordRequest = new PutRecordRequest();
		putRecordRequest.setData(encryptedData);
		putRecordRequest.setPartitionKey(String.valueOf(System.currentTimeMillis()));
		putRecordRequest.setStreamName(STREAM_NAME);
		PutRecordResult result = kinesis.putRecord(putRecordRequest);
		assertNotNull(result.getShardId());
		assertNotNull(result.getSequenceNumber());
		System.out.println("Done 1");
		kmsDecryption();
		
	}

	public void testLargeRecordEncryption() throws IOException
	{
		//final Map<String, String> context = Collections.singletonMap("Kinesis", "encryptionContext");
		//final KmsMasterKeyProvider prov = new KmsMasterKeyProvider(keyArn);
		Charset encoding = StandardCharsets.UTF_8.defaultCharset();
		PutRecordRequest putRecordRequest = new PutRecordRequest();
		String record = KinesisEncryptionUtils.readFile(filePath, encoding);
		int sizeOfRecord = KinesisEncryptionUtils.calculateSizeOfObject(record);
		String encryptedRecord = KinesisEncryptionUtils.toEncryptedString(crypto,record,prov,context);
		int sizeOfEncryptedRecord = KinesisEncryptionUtils.calculateSizeOfObject(encryptedRecord);
		System.out.println("Size of Record is : " + sizeOfRecord);
		System.out.println("Size of Encrypted Record is  : " + sizeOfEncryptedRecord);
		ByteBuffer buffer = KinesisEncryptionUtils.toEncryptedByteStream(encryptedRecord);
		Assert.assertTrue("Correct", sizeOfRecord < sizeOfEncryptedRecord);
		putRecordRequest.setData(buffer);
		putRecordRequest.setPartitionKey(String.valueOf(System.currentTimeMillis()));
		putRecordRequest.setStreamName(STREAM_NAME);
		PutRecordResult result = kinesis.putRecord(putRecordRequest);
		assertNotNull(result.getShardId());
		assertNotNull(result.getSequenceNumber());
		System.out.println("Done 1");
		String decodedRecord = decryptLargeRecord();
		Assert.assertEquals(record, decodedRecord);
		Assert.assertEquals(KinesisEncryptionUtils.calculateSizeOfObject(record), KinesisEncryptionUtils.calculateSizeOfObject(decodedRecord));


	}

	private String decryptLargeRecord()
	{
		List<Record> records;
		DescribeStreamResult streamResult = kinesis.describeStream(STREAM_NAME);
		StreamDescription streamDescription = streamResult.getStreamDescription();
		List<Shard> shardList = streamDescription.getShards();
		String shardIterator = null;
		GetShardIteratorRequest getShardIteratorRequest = new GetShardIteratorRequest();
		getShardIteratorRequest.setStreamName(STREAM_NAME);
		getShardIteratorRequest.setShardId(shardList.get(0).getShardId());
		getShardIteratorRequest.setShardIteratorType("TRIM_HORIZON");
		GetShardIteratorResult getShardIteratorResult = kinesis.getShardIterator(getShardIteratorRequest);
		shardIterator = getShardIteratorResult.getShardIterator();
		String decodedRecord = null;
		boolean exec = true;
		while (exec)
		{
			GetRecordsRequest getRecordsRequest = new GetRecordsRequest();
			getRecordsRequest.setShardIterator(shardIterator);
			getRecordsRequest.setLimit(1000);
			GetRecordsResult result = kinesis.getRecords(getRecordsRequest);
			records = result.getRecords();
			if(records.size() > 0)
			{
				for (Record record : records)
				{
					try
					{
						ByteBuffer data = record.getData();
						decodedRecord = KinesisEncryptionUtils.decryptByteStream(crypto,data,prov,keyArn, context);
						///assertEquals(largeRecord, decodedData);
						System.out.println("Found Record");
						exec=false;
						break;
					}
					catch (CharacterCodingException e)
					{
						System.out.println("Exception decoding record: " + record.getData() + "with Exception : " + e.toString());
					}
				}

			}
			System.out.println("Records Size is : " + records.size() + " Records : " + records.toString());
			try
			{
				Thread.sleep(600);
			}
			catch (InterruptedException exception)
			{
				throw new RuntimeException(exception);
			}
			shardIterator = result.getNextShardIterator();
		}
		System.out.println("Done");
		return decodedRecord;
	}
	
	private void kmsDecryption()
	{	
			List<Record> records = new ArrayList<Record>();
			DescribeStreamResult streamResult = kinesis.describeStream(STREAM_NAME);
			StreamDescription streamDescription = streamResult.getStreamDescription();
			List<Shard> shardList = streamDescription.getShards();
			String shardIterator = null;
			GetShardIteratorRequest getShardIteratorRequest = new GetShardIteratorRequest();
			getShardIteratorRequest.setStreamName(STREAM_NAME);
			getShardIteratorRequest.setShardId(shardList.get(0).getShardId());
			getShardIteratorRequest.setShardIteratorType("TRIM_HORIZON");
			GetShardIteratorResult getShardIteratorResult = kinesis.getShardIterator(getShardIteratorRequest);
			shardIterator = getShardIteratorResult.getShardIterator();
			boolean exec = true;
			while (exec) 
			{		
			  GetRecordsRequest getRecordsRequest = new GetRecordsRequest();
			  getRecordsRequest.setShardIterator(shardIterator);
			  getRecordsRequest.setLimit(1000); 
			  GetRecordsResult result = kinesis.getRecords(getRecordsRequest);		  
			  records = result.getRecords();
			  if(records.size() > 0)
			  {
				  for (Record record : records)
				  {    
						 try 
					     {
							  /*
							   * Now trying the KMS directly*/
							 DecryptRequest decrypter = new DecryptRequest().withCiphertextBlob(record.getData());
							 DecryptResult dresult = kms.decrypt(decrypter);
							 String decodedData = decoder.decode(dresult.getPlaintext()).toString();
							 System.out.println("Cipher Blob :" + record.getData().toString() + " : " + "Decrypted Text is :" 
							 + decodedData);
							 assertEquals(car.toString(), decodedData);
							 exec=false;
							 break;
						 } 
						 catch (CharacterCodingException e) 
						 {
							System.out.println("Exception decoding record: " + record.getData() + "with Exception : " + e.toString());	
						 }
				  }
				  
			  }
			  System.out.println("Records Size is : " + records.size() + " Records : " + records.toString());			  
			  try 
			  {
				  Thread.sleep(600);
			  } 
			  catch (InterruptedException exception) 
			  {
			    throw new RuntimeException(exception);
			  }
			shardIterator = result.getNextShardIterator();	
		   }		
			System.out.println("Done");	
	}
	
	private static boolean createStream(AmazonKinesisClient kinesis)
	{
		boolean isCreated = false;
		CreateStreamRequest createStreamRequest = new CreateStreamRequest();
		createStreamRequest.setStreamName(STREAM_NAME);
		createStreamRequest.setShardCount(1);
		kinesis.createStream( createStreamRequest );
		DescribeStreamRequest describeStreamRequest = new DescribeStreamRequest();
		describeStreamRequest.setStreamName(STREAM_NAME);

		long startTime = System.currentTimeMillis();
		long endTime = startTime + ( 10 * 60 * 1000 );
		while ( System.currentTimeMillis() < endTime ) {
		  try {
		    Thread.sleep(20 * 1000);
		  } 
		  catch ( Exception e ) {}
		  
		  try 
		  {
		    DescribeStreamResult describeStreamResponse = kinesis.describeStream( describeStreamRequest );
		    String streamStatus = describeStreamResponse.getStreamDescription().getStreamStatus();
		    if ( streamStatus.equals( "ACTIVE" ) ) 
		    {
		    	isCreated = true;
		      break;
		    }
		    //
		    // sleep for one second
		    //
		    try 
		    {
		      Thread.sleep( 1000 );
		    }
		    catch ( Exception e ) {}
		  }
		  catch (Exception e ) {}
		}
		if ( System.currentTimeMillis() >= endTime ) 
		{
		  throw new RuntimeException( "Stream " + STREAM_NAME + " never went active" );
		}
	 return isCreated;
	}
	

}
