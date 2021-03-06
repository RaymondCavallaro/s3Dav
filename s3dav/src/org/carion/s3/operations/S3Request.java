/*
 * Copyright (c) 2006, Pierre Carion.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.carion.s3.operations;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.carion.s3.Credential;
import org.carion.s3.S3Log;
import org.carion.s3.util.Base64;
import org.carion.s3.util.InputStreamObserver;
import org.carion.s3.util.Util;
import org.carion.s3dav.Version;

public class S3Request {
    private static final String METADATA_PREFIX = "x-amz-meta-";

    private static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";

    public enum S3RequestMethods {
    	GET("GET"), PUT("PUT"), DELETE("DELETE"), HEAD("HEAD");
    	
    	public final String method;
    	
    	private S3RequestMethods(String method) {
    		this.method = method;
    	}
    }

    private final String _method;

    private final String _path;

    private final String _httpDate;

    private final Map _metaInfos = new HashMap();

    private final S3Log _log;

    private String _queryString = null;

    private InputStream _content = null;

    private String _contentType = null;

    private String _contentMd5 = null;

    private long _contentLength = -1;

    private UploadNotification _notify = null;

    private final static SimpleDateFormat _httpDateFormat;

    static {
        _httpDateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss ", Locale.US);
        _httpDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    public S3Request(S3RequestMethods method, String path, S3Log log) {
        this(method.method, path, _httpDateFormat.format(new Date()) + "GMT", log);
    }

    private S3Request(String method, String path, String date, S3Log log) {
        _method = method;
        _path = path;
        _httpDate = date;
        _log = log;
    }

    void setUploadNotification(UploadNotification notify) {
        _notify = notify;
    }

    public void setQueryString(String queryString) {
        _queryString = queryString;
    }

    void setContent(InputStream in, String contentMd5, String contentType,
            long contentLength) {
        _content = in;
        _contentType = contentType;
        _contentMd5 = contentMd5;
        _contentLength = contentLength;
    }

    void addMetaInformation(String key, String value) {
        addHeader(METADATA_PREFIX + key, value);
    }

    public boolean process(Credential credential, S3Processing processing,
            boolean doCloseConnection) {
        HttpURLConnection conn = null;
        try {
            // on these methods, the java.net classes will add this content type
            // automatically so we need to use to have a goog hmac sha1 key
            if (_contentType == null) {
                // CARION - 2006/04/03
                // It seems that with JDK 1.5, the content-type is
                // see to x-www-form-urlencoded for the HEAD requests
                // to make thins simpler, I've decided to always set
                // the content type to x-www-form-urlencoded when the
                // content-type was not set
                _contentType = "application/x-www-form-urlencoded";
            }

            String canon = makeCanonicalString();
            String hmacSha1 = hmacSha1(credential.getAwsSecretAccessKey(),
                    canon);

            URL url = new URL("https://" + credential.getHost() + _path
        	        + ((_queryString != null) ? "?" + _queryString : ""));

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(_method);

            conn.setRequestProperty("Authorization", "AWS "
                    + credential.getAwsAccessKeyId() + ":" + hmacSha1);

            conn.setRequestProperty("Date", _httpDate);

            conn.setRequestProperty("User-Agent", Version.USER_AGENT);

            if (_contentMd5 != null) {
                conn.setRequestProperty("Content-MD5", _contentMd5);
            }

            if (_contentType != null) {
                conn.setRequestProperty("Content-Type", _contentType);
            }

            if (_content != null) {
                conn.setRequestProperty("Content-Length", String
                        .valueOf(_contentLength));
            } else {
                conn.setRequestProperty("Content-Length", "0");
            }

            for (Iterator i = _metaInfos.keySet().iterator(); i.hasNext();) {
                String key = (String) i.next();
                List s = (List) _metaInfos.get(key);
                conn.setRequestProperty(key, concatenateList(s));
            }

            if (_content != null) {
                conn.setDoOutput(true);
            }

            conn.connect();

            if (_content != null) {
                OutputStream dataout = conn.getOutputStream();
                int len = 0;
                byte[] data = new byte[1024];
                _log.log("Starting copy of content");
                while ((len = _content.read(data)) >= 0) {
                    dataout.write(data, 0, len);
                    dataout.flush();
                    if (_notify != null) {
                        if (!_notify.ntfUploaded(len)) {
                            try {
                                dataout.close();
                            } catch (Exception ex) {
                            }
                            try {
                                _content.close();
                            } catch (Exception ex) {
                            }
                            throw new IOException("upload aborted");
                        }
                    }
                }
                _log.log("@@ content copied over. Closing connection");
                try {
                    _content.close();
                } catch (IOException ex) {
                    _log.log("Error closing inputstream", ex);
                }
                dataout.close();
            }

            int responseCode = conn.getResponseCode();
            String amzRequestId = conn.getHeaderField("x-amz-request-id");
            String amzId2 = conn.getHeaderField("x-amz-id-2");
            String contentType = conn.getContentType();

            // get the meta information from header
            for (int i = 0;; i++) {
                String key = conn.getHeaderFieldKey(i);
                if (key == null) {
                    // Warning: javadoc for getHeaderFieldKey:
                    // Returns the key for the nth header field.
                    // Some implementations may treat the 0th header field as
                    // special,
                    // i.e. as the status line returned by the HTTP server. In
                    // this
                    // case, getHeaderField(0) returns the status line, but
                    // getHeaderFieldKey(0) returns null.
                    if (i > 0) {
                        break;
                    } else {
                        continue;
                    }
                }
                String value = conn.getHeaderField(i);
                if (key.startsWith(METADATA_PREFIX)) {
                    processing.amzMeta(key.substring(METADATA_PREFIX.length()),
                            value);
                } else {
                    processing.amzHeader(key, value);
                }
            }

            // 2xx response codes are ok, everything else is an error
            if (responseCode / 100 != 2) {
                String error = "";
                if (conn.getErrorStream() != null) {
                    InputStream in = Util.wrap(conn.getErrorStream(), false,
                            conn.getContentLength());
                    error = Util.readInputStreamAsString(in);
                    _log.log(error);
                }
                S3Error errorResponse;
                if ("application/xml".equals(contentType)
                        && (error.length() > 2)) {
                    S3ResponseParser parser = new S3ResponseParser(error);
                    errorResponse = parser.parseError();
                } else {
                    errorResponse = new S3ErrorImpl().setMessage(error);
                }
                processing.amzError(responseCode, errorResponse, amzRequestId,
                        amzId2);
                return false;
            } else {
                processing.amzOk(responseCode, amzRequestId, amzId2);
            }

            if (!_method.equals("HEAD")) {
                InputStreamObserver observer = null;
                if (!doCloseConnection) {
                    observer = new ConnectionCloser(conn);
                }
                InputStream in = Util.wrap(conn.getInputStream(), false, conn
                        .getContentLength(), observer);
                processing.amzInputStream(in);
            }
            return true;
        } catch (Exception ex) {
            processing.amzException(ex);
            return false;
        } finally {
            // in case or the connection can be left opened,
            // we check if the processing want's to keep
            // connection opened or not !
            // When we do a Object GET, we want to keep the
            // InputStream opened as the content of this stream
            // will be pushed to the webDAV assiciated GET
            // request
            if (doCloseConnection) {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ex) {
                    }
                }
            }
        }
    }

    void addHeader(String key, String value) {
        Object data = _metaInfos.get(key);
        List values;
        if (data == null) {
            values = new ArrayList();
        } else {
            values = (List) data;
        }
        values.add(value);
        _metaInfos.put(key, values);
    }

    String makeCanonicalString() {
        StringBuffer buf = new StringBuffer();

        buf.append(_method);
        buf.append("\n");

        if (_contentMd5 != null) {
            buf.append(_contentMd5);
        }
        buf.append("\n");

        if (_contentType != null) {
            buf.append(_contentType);
        }
        buf.append("\n");

        if (_httpDate != null) {
            buf.append(_httpDate);
        }
        buf.append("\n");

        SortedMap headers = new TreeMap();
        for (Iterator i = _metaInfos.keySet().iterator(); i.hasNext();) {
            String key = (String) i.next();
            String lk = key.toLowerCase();

            List s = (List) _metaInfos.get(key);
            headers.put(lk, concatenateList(s));
        }

        for (Iterator i = headers.keySet().iterator(); i.hasNext();) {
            String key = (String) i.next();
            buf.append(key).append(':').append(headers.get(key));
            buf.append("\n");
        }

        // don't include the query parameters...
        int queryIndex = _path.indexOf('?');
        if (queryIndex == -1) {
            buf.append(_path);
        } else {
            buf.append(_path.substring(0, queryIndex));
        }

        // ...unless there is an acl or torrent parameter
        if (_path.matches(".*[&?]acl($|=|&).*")) {
            buf.append("?acl");
        } else if (_path.matches(".*[&?]torrent($|=|&).*")) {
            buf.append("?torrent");
        }

        return buf.toString();
    }

    private String concatenateList(List values) {
        StringBuffer buf = new StringBuffer();
        for (int i = 0, size = values.size(); i < size; ++i) {
            buf.append(((String) values.get(i)).replaceAll("\n", "").trim());
            if (i != (size - 1)) {
                buf.append(",");
            }
        }
        return buf.toString();
    }

    /**
     * Calculate the HMAC/SHA1 on a string.
     * 
     * @param data
     *            Data to sign
     * @param passcode
     *            Passcode to sign it with
     * @return Signature
     * @throws NoSuchAlgorithmException
     *             If the algorithm does not exist. Unlikely
     * @throws InvalidKeyException
     *             If the key is invalid.
     */
    String hmacSha1(String awsSecretAccessKey, String canonicalString) {
        // Acquire an HMAC/SHA1 from the raw key bytes.
        SecretKeySpec signingKey = new SecretKeySpec(awsSecretAccessKey
                .getBytes(), HMAC_SHA1_ALGORITHM);

        // Acquire the MAC instance and initialize with the signing key.
        Mac mac = null;
        try {
            mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // should not happen
            throw new RuntimeException("Could not find sha1 algorithm", e);
        }
        try {
            mac.init(signingKey);
        } catch (InvalidKeyException e) {
            // also should not happen
            throw new RuntimeException(
                    "Could not initialize the MAC algorithm", e);
        }

        // Compute the HMAC on the digest, and set it.
        String b64 = Base64
                .encodeBytes(mac.doFinal(canonicalString.getBytes()));

        return b64;
    }

    public String getMethod() {
        return _method;
    }

    public String getPath() {
        return _path;
    }

    public String getQueryString() {
        if (_queryString == null) {
            return "";
        } else {
            return _queryString;
        }
    }

    // from: http://javaalmanac.com/egs/java.nio/Buffer2Stream.html
    private InputStream mkInputStream(final ByteBuffer buf) {
        return new InputStream() {
            public synchronized int read() throws IOException {
                if (!buf.hasRemaining()) {
                    return -1;
                }
                return buf.get();
            }

            public synchronized int read(byte[] bytes, int off, int len)
                    throws IOException {
                // Read only what's left
                len = Math.min(len, buf.remaining());
                if (len == 0) {
                    return -1;
                }
                buf.get(bytes, off, len);
                return len;
            }

            public void close() throws IOException {
            }
        };
    }

    private class ConnectionCloser implements InputStreamObserver {
        private final HttpURLConnection _conn;

        ConnectionCloser(HttpURLConnection conn) {
            _conn = conn;
        }

        public void closeConnection() {
            if (_conn != null) {
                try {
                    _conn.disconnect();
                } catch (Exception ex) {
                }
            }
        }
    }
}
