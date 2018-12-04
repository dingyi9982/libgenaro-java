# libgenaro-java (SHIL)

Asynchronous Java library and CLI for encrypted file transfer on the Genaro network. It's developed on Java 8, but should be able to run on latest android. Compatible with [libgenaro](https://github.com/GenaroNetwork/libgenaro).

## Feature Finished

- Load wallet from json file with password
- Use wallet to sign request for user authentication
- Delete bucket/rename bucket/list buckets/list files/delete file/download file
- Asynchronous I/O with concurrent peer-to-peer network requests for shards
- File encryption with AES-256-CTR
- File name and bucket name encryption with AES-256-GCM
- Asynchronous progress updates
- Seed based file encryption key for portability between devices
- File integrity and authenticity verified with HMAC-SHA512

## Feature Todo

- Erasure encoding with reed solomon for data reliability
- Retry when fail
- Better exception handling
- Exchange reports with bridge
- Cli interface

## 3rd party dependencies

This library use Java Future/Execution framework, and the following 3rd party libs.

- [Bouncy Castle](https://www.bouncycastle.org/java.html) for crypto algorithms.
- [web3j](https://github.com/web3j/web3j) for wallet managment, BIP39 and Interaction with blockchain.
- [JavaReedSolomon](https://github.com/Backblaze/JavaReedSolomon) for reed solomon algorithm.
- [jackson](https://github.com/FasterXML/jackson) for JSON parse/compose.
- [okhttp3](https://github.com/square/okhttp) as HTTP client.
- [guava](https://github.com/google/guava) and [apache.commons](https://commons.apache.org/) as utility.
- [log4j](https://logging.apache.org/log4j) for logging.
- [testng](https://testng.org/doc/index.html) for testing.
- [maven](https://maven.apache.org/) for dependency managment.

## Example Usage

Initialize:

```java
String V3JSON = "{ \"address\": \"aaad65391d2d2eafda9b27326d1e81d52a6a3dc8\",
        \"crypto\": { \"cipher\": \"aes-128-ctr\",
        \"ciphertext\": \"e968751f3d60827b6e62e3ff6c024ecc82f33a6c55428be33249c83edba444ca\",
        \"cipherparams\": { \"iv\": \"e80d9ec9ba6241a143c756ec78066ad9\" }, \"kdf\": \"scrypt\",
        \"kdfparams\": { \"dklen\": 32, \"n\": 262144, \"p\": 1, \"r\": 8, \"salt\":
        \"ea7cb2b004db67d3103b3790caced7a96b636762f280b243e794fb5bef8ef74b\" },
        \"mac\": \"ceb3789e77be8f2a7ab4d205bf1b54e048ad3f5b080b96e07759de7442e050d2\" },
        \"id\": \"e28f31b4-1f43-428b-9b12-ab586638d4b1\", \"version\": 3 }";
String passwd = "xxxxxx";
Genaro api = new Genaro("http://192.168.50.206:8080");
GenaroWallet gw = new GenaroWallet(V3JSON, passwd);
api.logIn(gw);
```

Upload file:

```java
String bucketId = "5bfcf4ea7991d267f4eb53b4";
String filePath = "xxxxxxxxx";
String fileName = "xxx";
new Uploader(api, false, filePath, fileName, bucketId, new Progress() {
    @Override
    public void onBegin() {
    }
    @Override
    public void onEnd(int status) {
    }
    @Override
    public void onProgress(float progress, String message) {
    }
}).start();
```

Download file:

```java
String V3JSON = "{ \"address\": \"aaad65391d2d2eafda9b27326d1e81d52a6a3dc8\",
        \"crypto\": { \"cipher\": \"aes-128-ctr\",
        \"ciphertext\": \"e968751f3d60827b6e62e3ff6c024ecc82f33a6c55428be33249c83edba444ca\",
        \"cipherparams\": { \"iv\": \"e80d9ec9ba6241a143c756ec78066ad9\" }, \"kdf\": \"scrypt\",
        \"kdfparams\": { \"dklen\": 32, \"n\": 262144, \"p\": 1, \"r\": 8, \"salt\":
        \"ea7cb2b004db67d3103b3790caced7a96b636762f280b243e794fb5bef8ef74b\" },
        \"mac\": \"ceb3789e77be8f2a7ab4d205bf1b54e048ad3f5b080b96e07759de7442e050d2\" },
        \"id\": \"e28f31b4-1f43-428b-9b12-ab586638d4b1\", \"version\": 3 }";

String passwd = "xxxxxx";

Genaro api = new Genaro("http://192.168.50.206:8080");
GenaroWallet gw = new GenaroWallet(V3JSON, passwd);
api.logIn(gw);

String bucketId = "5bfcf4ea7991d267f4eb53b4";
String fileId = "5c0103fd5a158a5612e67461";
String filePath = "xxxxxxxxx";
new Downloader(api, bucketId, fileId, filePath, new Progress() {
    @Override
    public void onBegin() {
    }

    @Override
    public void onEnd(int status) {
    }

    @Override
    public void onProgress(float progress, String message) {
    }
}).start();
```