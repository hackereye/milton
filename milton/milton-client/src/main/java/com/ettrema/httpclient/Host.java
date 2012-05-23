package com.ettrema.httpclient;

import com.bradmcevoy.common.Path;
import com.bradmcevoy.http.DateUtils;
import com.bradmcevoy.http.DateUtils.DateParseException;
import com.bradmcevoy.http.Range;
import com.bradmcevoy.http.Response;
import com.bradmcevoy.http.exceptions.BadRequestException;
import com.bradmcevoy.http.exceptions.ConflictException;
import com.bradmcevoy.http.exceptions.NotAuthorizedException;
import com.bradmcevoy.http.exceptions.NotFoundException;
import com.ettrema.cache.Cache;
import com.ettrema.cache.MemoryCache;
import com.ettrema.common.LogUtils;
import com.ettrema.httpclient.Utils.CancelledException;
import com.ettrema.httpclient.zsyncclient.FileSyncer;
import java.io.*;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.xml.namespace.QName;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.*;
import org.apache.http.auth.*;
import org.apache.http.client.*;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.entity.StringEntity;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestExecutor;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.output.XMLOutputter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author mcevoyb
 */
public class Host extends Folder {

    public static List<QName> defaultFields = Arrays.asList(
            RespUtils.davName("resourcetype"), 
            RespUtils.davName("displayname"), 
            RespUtils.davName("getcontentlength"),
            RespUtils.davName("creationdate"), 
            RespUtils.davName("getlastmodified"),
            RespUtils.davName("iscollection"),
            RespUtils.davName("lockdiscovery")
            );    

    private static String LOCK_XML = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
            + "<D:lockinfo xmlns:D='DAV:'>"
            + "<D:lockscope><D:exclusive/></D:lockscope>"
            + "<D:locktype><D:write/></D:locktype>"
            + "<D:owner>${owner}</D:owner>"
            + "</D:lockinfo>";
    private static final Logger log = LoggerFactory.getLogger(Host.class);
    public final String server;
    public final Integer port;
    public final String user;
    public final String password;
    public final String rootPath;
    /**
     * time in milliseconds to be used for all timeout parameters
     */
    private int timeout;
    private final DefaultHttpClient client;
    private final TransferService transferService;
    private final FileSyncer fileSyncer;
    private final List<ConnectionListener> connectionListeners = new ArrayList<ConnectionListener>();
    
    private boolean secure; // use HTTPS if true

    static {
//    System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.SimpleLog");
//    System.setProperty("org.apache.commons.logging.simplelog.showdatetime", "true");
//    System.setProperty("org.apache.commons.logging.simplelog.log.httpclient.wire.header", "debug");
//    System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.commons.httpclient", "debug");    
    }

    public Host(String server, Integer port, String user, String password, ProxyDetails proxyDetails) {
        this(server, null, port, user, password, proxyDetails, 30000, null, null);
    }

    public Host(String server, Integer port, String user, String password, ProxyDetails proxyDetails, Cache<Folder, List<Resource>> cache) {
        this(server, null, port, user, password, proxyDetails, 30000, cache, null); // defaul timeout of 30sec
    }

    public Host(String server, String rootPath, Integer port, String user, String password, ProxyDetails proxyDetails, Cache<Folder, List<Resource>> cache) {
        this(server, rootPath, port, user, password, proxyDetails, 30000, cache, null); // defaul timeout of 30sec
    }

    public Host(String server, String rootPath, Integer port, String user, String password, ProxyDetails proxyDetails, int timeoutMillis, Cache<Folder, List<Resource>> cache, FileSyncer fileSyncer) {
        super((cache != null ? cache : new MemoryCache<Folder, List<Resource>>("resource-cache-default", 50, 20)));
        if (server == null) {
            throw new IllegalArgumentException("host name cannot be null");
        }
        this.rootPath = rootPath;
        this.timeout = timeoutMillis;
        this.server = server;
        this.port = port;
        this.user = user;
        this.password = password;
        client = new MyDefaultHttpClient();
        HttpRequestRetryHandler handler = new NoRetryHttpRequestRetryHandler();
        client.setHttpRequestRetryHandler(handler);
        HttpParams params = client.getParams();
        HttpConnectionParams.setConnectionTimeout(params, 10000);
        HttpConnectionParams.setSoTimeout(params, 10000);

        if (user != null) {
            client.getCredentialsProvider().setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, password));
            PreemptiveAuthInterceptor interceptor = new PreemptiveAuthInterceptor();
            client.addRequestInterceptor(interceptor);
        }


        if (proxyDetails != null) {
            if (proxyDetails.isUseSystemProxy()) {
                System.setProperty("java.net.useSystemProxies", "true");
            } else {
                System.setProperty("java.net.useSystemProxies", "false");
                if (proxyDetails.getProxyHost() != null && proxyDetails.getProxyHost().length() > 0) {
                    HttpHost proxy = new HttpHost(proxyDetails.getProxyHost(), proxyDetails.getProxyPort(), "http");
                    client.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
                    if (proxyDetails.hasAuth()) {
                        client.getCredentialsProvider().setCredentials(
                                new AuthScope(proxyDetails.getProxyHost(), proxyDetails.getProxyPort()),
                                new UsernamePasswordCredentials(proxyDetails.getUserName(), proxyDetails.getPassword()));
                    }
                }
            }
        }
        transferService = new TransferService(client, connectionListeners);
        transferService.setTimeout(timeoutMillis);
        this.fileSyncer = fileSyncer;
    }

    /**
     * Finds the resource by iterating through the path parts resolving
     * collections as it goes. If any path component is not founfd returns null
     *
     * @param path
     * @return
     * @throws IOException
     * @throws com.ettrema.httpclient.HttpException
     */
    public Resource find(String path) throws IOException, com.ettrema.httpclient.HttpException, NotAuthorizedException, BadRequestException {
        return find(path, false);
    }

    public Resource find(String path, boolean invalidateCache) throws IOException, com.ettrema.httpclient.HttpException, NotAuthorizedException, BadRequestException {
        if (path == null || path.length() == 0 || path.equals("/")) {
            return this;
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] arr = path.split("/");
        return _find(this, arr, 0, invalidateCache);
    }

    public static Resource _find(Folder parent, String[] arr, int i, boolean invalidateCache) throws IOException, com.ettrema.httpclient.HttpException, NotAuthorizedException, BadRequestException {
        String childName = arr[i];
        if (invalidateCache) {
            parent.flush();
        }
        Resource child = parent.child(childName);
        if (i == arr.length - 1) {
            return child;
        } else {
            if (child instanceof Folder) {
                return _find((Folder) child, arr, i + 1, invalidateCache);
            } else {
                return null;
            }
        }
    }

    /**
     * Find a folder at the given path. Is much the same as find(path), except
     * that it throws an exception if the resource is not a folder
     *
     * @param path
     * @return
     * @throws IOException
     * @throws com.ettrema.httpclient.HttpException
     * @throws NotAuthorizedException
     * @throws BadRequestException
     */
    public Folder getFolder(String path) throws IOException, com.ettrema.httpclient.HttpException, NotAuthorizedException, BadRequestException {
        Resource res = find(path);
        if (res instanceof Folder) {
            return (Folder) res;
        } else {
            throw new RuntimeException("Not a folder: " + res.href());
        }
    }

    /**
     * Create a collection at the given absolute path. This path is NOT relative
     * to the host's base path
     *
     * @param newUri
     * @return
     * @throws com.ettrema.httpclient.HttpException
     * @throws NotAuthorizedException
     * @throws ConflictException
     * @throws BadRequestException
     * @throws NotFoundException
     * @throws URISyntaxException
     */
    public synchronized int doMkCol(Path newUri) throws com.ettrema.httpclient.HttpException, NotAuthorizedException, ConflictException, BadRequestException, NotFoundException, URISyntaxException {
        String url = this.buildEncodedUrl(newUri);
        return doMkCol(url);
    }

    /**
     *
     * @param newUri - must be fully qualified and correctly encoded
     * @return
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized int doMkCol(String newUri) throws com.ettrema.httpclient.HttpException, NotAuthorizedException, ConflictException, BadRequestException, NotFoundException, URISyntaxException {
        notifyStartRequest();
        MkColMethod p = new MkColMethod(newUri);
        try {
            int result = Utils.executeHttpWithStatus(client, p, null);
            if (result == 409) {
                // probably means the folder already exists
                p.abort();
                return result;
            }
            Utils.processResultCode(result, newUri);
            return result;
        } catch (IOException ex) {
            p.abort();
            throw new RuntimeException(ex);
        } finally {
            notifyFinishRequest();
        }
    }

    /**
     * Returns the lock token, which must be retained to unlock the resource
     *
     * @param uri - must be encoded
     * @param owner
     * @return
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized String doLock(String uri) throws com.ettrema.httpclient.HttpException, NotAuthorizedException, ConflictException, BadRequestException, NotFoundException, URISyntaxException {
        notifyStartRequest();
        LockMethod p = new LockMethod(uri);
        try {
            String lockXml = LOCK_XML.replace("${owner}", user);
            HttpEntity requestEntity = new StringEntity(lockXml, "UTF-8");
            p.setEntity(requestEntity);
            HttpResponse resp = host().client.execute(p);
            int result = resp.getStatusLine().getStatusCode();
            Utils.processResultCode(result, uri);
            return p.getLockToken(resp);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            notifyFinishRequest();
        }
    }

    /**
     *
     * @param uri - must be encoded
     * @param lockToken
     * @return
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized int doUnLock(String uri, String lockToken) throws com.ettrema.httpclient.HttpException, NotAuthorizedException, ConflictException, BadRequestException, NotFoundException, URISyntaxException {
        notifyStartRequest();
        UnLockMethod p = new UnLockMethod(uri, lockToken);
        try {
            int result = Utils.executeHttpWithStatus(client, p, null);
            Utils.processResultCode(result, uri);
            return result;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            notifyFinishRequest();
        }
    }

    /**
     *
     * @param path - an Un-encoded path. Eg /a/b c/ = /a/b%20c/
     * @param content
     * @param contentLength
     * @param contentType
     * @return
     */
    public HttpResult doPut(Path path, InputStream content, Long contentLength, String contentType) {
        String dest = buildEncodedUrl(path);
        return doPut(dest, content, contentLength, contentType, null);
    }

    public HttpResult doPut(Path path, byte[] data, String contentType) {
        String dest = buildEncodedUrl(path);
        LogUtils.trace(log, "doPut: ", dest);
        notifyStartRequest();
        HttpPut p = new HttpPut(dest);

        // Dont use transferService so we can use byte array
        try {
            ByteArrayEntity requestEntity = new ByteArrayEntity(data);
            requestEntity.setContentType(contentType);
            p.setEntity(requestEntity);
            HttpResult result = Utils.executeHttpWithResult(client, p, null);
            return result;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            notifyFinishRequest();
        }
    }

    /**
     *
     * @param newUri
     * @param file
     * @param listener
     * @return - the result code
     * @throws FileNotFoundException
     * @throws HttpException
     */
    public HttpResult doPut(Path remotePath, java.io.File file, ProgressListener listener) throws FileNotFoundException, HttpException, CancelledException, NotAuthorizedException, ConflictException {
        if (fileSyncer != null) {
            try {
                fileSyncer.upload(this, file, remotePath, listener);
                LogUtils.trace(log, "doPut: uploaded");
                return new HttpResult(Response.Status.SC_OK.code, null);
            } catch (NotFoundException e) {
                // ZSync file was not found
                log.trace("Not found: " + remotePath);
            } catch (IOException ex) {
                throw new GenericHttpException(remotePath.toString(), ex);
            }
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            String dest = buildEncodedUrl(remotePath);
            return doPut(dest, in, file.length(), null, listener);
        } finally {
            IOUtils.closeQuietly(in);
        }

    }

    /**
     * Uploads the data. Does not do any file syncronisation
     *
     * @param newUri - encoded full URL
     * @param content
     * @param contentLength
     * @param contentType
     * @return - the result code
     */
    public synchronized HttpResult doPut(String newUri, InputStream content, Long contentLength, String contentType, ProgressListener listener) {
        LogUtils.trace(log, "doPut", newUri);
        return transferService.put(newUri, content, contentLength, contentType, listener);
    }

    /**
     *
     * @param from - encoded source url
     * @param newUri - encoded destination
     * @return
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized int doCopy(String from, String newUri) throws com.ettrema.httpclient.HttpException, NotAuthorizedException, ConflictException, BadRequestException, NotFoundException, URISyntaxException {
        notifyStartRequest();
        CopyMethod m = new CopyMethod(from, newUri);
        try {
            int res = Utils.executeHttpWithStatus(client, m, null);
            Utils.processResultCode(res, from);
            return res;
        } catch (HttpException ex) {
            throw new RuntimeException(ex);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            notifyFinishRequest();
        }

    }

    /**
     * Deletes the item at the given path, relative to the root path of this host
     * 
     * @param path - unencoded and relative to Host's rootPath
     * @return
     * @throws IOException
     * @throws com.ettrema.httpclient.HttpException
     * @throws NotAuthorizedException
     * @throws ConflictException
     * @throws BadRequestException
     * @throws NotFoundException 
     */
    public synchronized int doDelete(Path path) throws IOException, com.ettrema.httpclient.HttpException, NotAuthorizedException, ConflictException, BadRequestException, NotFoundException {
        Path root = Path.path(rootPath);
        Path p = root.add(path);
        String dest = buildEncodedUrl(p);
        return doDelete(dest);
    }

    /**
     *
     * @param url - encoded url
     * @return
     * @throws IOException
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized int doDelete(String url) throws IOException, com.ettrema.httpclient.HttpException, NotAuthorizedException, ConflictException, BadRequestException, NotFoundException {
        notifyStartRequest();
        HttpDelete m = new HttpDelete(url);
        try {
            int res = Utils.executeHttpWithStatus(client, m, null);
            Utils.processResultCode(res, url);
            return res;
        } catch (HttpException ex) {
            throw new RuntimeException(ex);
        } finally {
            notifyFinishRequest();
        }
    }

    /**
     *
     * @param sourceUrl - encoded source url
     * @param newUri - encoded destination url
     * @return
     * @throws IOException
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized int doMove(String sourceUrl, String newUri) throws IOException, com.ettrema.httpclient.HttpException, NotAuthorizedException, ConflictException, BadRequestException, NotFoundException, URISyntaxException {
        notifyStartRequest();
        MoveMethod m = new MoveMethod(sourceUrl, newUri);
        try {
            int res = Utils.executeHttpWithStatus(client, m, null);
            Utils.processResultCode(res, sourceUrl);
            return res;
        } finally {
            notifyFinishRequest();
        }
    }
    
    public synchronized List<PropFindResponse> propFind(Path path, int depth, QName ... fields) throws IOException, com.ettrema.httpclient.HttpException, NotAuthorizedException, BadRequestException {
        List<QName> list = new ArrayList<QName>();
        list.addAll(Arrays.asList(fields));
        return propFind(path, depth, list);
    }

    /**
     * 
     * @param path - unencoded path, which will be evaluated relative to this Host's basePath
     * @param depth - 1 is to find immediate children, 2 includes their children, etc
     * @param fields - the list of fields to get, or null to use default fields
     * @return
     * @throws IOException
     * @throws com.ettrema.httpclient.HttpException
     * @throws NotAuthorizedException
     * @throws BadRequestException 
     */
    public synchronized List<PropFindResponse> propFind(Path path, int depth, List<QName> fields) throws IOException, com.ettrema.httpclient.HttpException, NotAuthorizedException, BadRequestException {
        Path base = Path.path(rootPath);
        Path p = base.add(path);
        String url = buildEncodedUrl(p);
        return _doPropFind(url, depth, fields);
    }

    /**
     *
     * @param url - the encoded absolute URL to query. This method does not apply basePath
     * @param depth - depth to generate responses for. Zero means only the
     * specified url, 1 means it and its direct children, etc
     * @return
     * @throws IOException
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized List<PropFindResponse> _doPropFind(String url, final int depth, List<QName> fields) throws IOException, com.ettrema.httpclient.HttpException, NotAuthorizedException, BadRequestException {
        log.trace("doPropFind: " + url);
        notifyStartRequest();
        final PropFindMethod m = new PropFindMethod(url);
        m.addHeader("Depth", depth + "");

        try {
            if (fields != null) {
                String propFindXml = buildPropFindXml(fields);
                HttpEntity requestEntity = new StringEntity(propFindXml, "UTF-8");
                m.setEntity(requestEntity);
            }

            final ByteArrayOutputStream bout = new ByteArrayOutputStream();
            final List<PropFindResponse> responses = new ArrayList<PropFindResponse>();
            ResponseHandler<Integer> respHandler = new ResponseHandler<Integer>() {

                @Override
                public Integer handleResponse(HttpResponse response) throws ClientProtocolException, IOException {
                    Header serverDateHeader = response.getFirstHeader("Date");
                    if (response.getStatusLine().getStatusCode() == 207) {
                        HttpEntity entity = response.getEntity();
                        if (entity != null) {
                            entity.writeTo(bout);
                            ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
                            Document document = getResponseAsDocument(bin);
                            String sServerDate = null;
                            if (serverDateHeader != null) {
                                sServerDate = serverDateHeader.getValue();
                            }
                            Date serverDate = null;
                            if (sServerDate != null && sServerDate.length() > 0) {
                                try {
                                    serverDate = DateUtils.parseWebDavDate(sServerDate);
                                } catch (DateParseException ex) {
                                    log.warn("Couldnt parse date header: " + sServerDate);
                                }
                            }
                            
                            buildResponses(document, serverDate, responses, depth);

                        }
                    }
                    return response.getStatusLine().getStatusCode();
                }
            };
            Integer res = client.execute(m, respHandler);

            Utils.processResultCode(res, url);
            return responses;
        } catch (ConflictException ex) {
            throw new RuntimeException(ex);
        } catch (NotFoundException e) {
            log.trace("not found: " + url);
            return null;
        } catch (HttpException ex) {
            throw new RuntimeException(ex);
        } finally {
            notifyFinishRequest();
        }
    }

    /**
     *
     * @return - child responses only, not the requested url
     */
    public void buildResponses(Document document, Date serverDate, List<PropFindResponse> responses, int depth) {
        Element root = document.getRootElement();
        List<Element> responseEls = RespUtils.getElements(root, "response");
        boolean isFirst = true;
        for (Element el : responseEls) {
            if (!isFirst || depth == 0) { // if depth=0 must return first and only result
                PropFindResponse resp = new PropFindResponse(serverDate, el);
                responses.add(resp);
            } else {
                isFirst = false;
            }
        }

    }

    public Document getResponseAsDocument(InputStream in) throws IOException {
//        IOUtils.copy( in, out );
//        String xml = out.toString();
        try {
            Document document = RespUtils.getJDomDocument(in);
            return document;
        } catch (JDOMException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     *
     * @param url - fully qualified and encoded URL
     * @param receiver
     * @param rangeList - if null does a normal GET request
     * @throws com.ettrema.httpclient.HttpException
     * @throws com.ettrema.httpclient.Utils.CancelledException
     */
    public synchronized void doGet(String url, StreamReceiver receiver, List<Range> rangeList, ProgressListener listener) throws com.ettrema.httpclient.HttpException, Utils.CancelledException, NotAuthorizedException, BadRequestException, ConflictException, NotFoundException {
        transferService.get(url, receiver, rangeList, listener);
    }

    /**
     *
     * @param path - the path to get, relative to the base path of the host
     * @param file - the file to write content to
     * @param listener
     * @throws IOException
     * @throws NotFoundException
     * @throws com.ettrema.httpclient.HttpException
     * @throws com.ettrema.httpclient.Utils.CancelledException
     * @throws NotAuthorizedException
     * @throws BadRequestException
     * @throws ConflictException
     */
    public synchronized void doGet(Path path, final java.io.File file, ProgressListener listener) throws IOException, NotFoundException, com.ettrema.httpclient.HttpException, CancelledException, NotAuthorizedException, BadRequestException, ConflictException {
        LogUtils.trace(log, "doGet", path);
        if (fileSyncer != null) {
            fileSyncer.download(this, path, file, listener);
        } else {
            String url = this.buildEncodedUrl(path);
            transferService.get(url, new StreamReceiver() {

                @Override
                public void receive(InputStream in) throws IOException {
                    OutputStream out = null;
                    BufferedOutputStream bout = null;
                    try {
                        out = FileUtils.openOutputStream(file);
                        bout = new BufferedOutputStream(out);
                        IOUtils.copy(in, bout);
                        bout.flush();
                    } finally {
                        IOUtils.closeQuietly(bout);
                        IOUtils.closeQuietly(out);
                    }

                }
            }, null, listener);
        }
    }

    public synchronized byte[] doGet(Path path) throws IOException, NotFoundException, com.ettrema.httpclient.HttpException, CancelledException, NotAuthorizedException, BadRequestException, ConflictException {
        return doGet(path, null);
    }

    public synchronized byte[] doGet(Path path, Map<String, String> queryParams) throws IOException, NotFoundException, com.ettrema.httpclient.HttpException, CancelledException, NotAuthorizedException, BadRequestException, ConflictException {
        LogUtils.trace(log, "doGet", path);
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        doGet(path, bout, queryParams);
        return bout.toByteArray();

    }

    public synchronized void doGet(Path path, final OutputStream out, Map<String, String> queryParams) throws IOException, NotFoundException, com.ettrema.httpclient.HttpException, CancelledException, NotAuthorizedException, BadRequestException, ConflictException {
        String url = this.buildEncodedUrl(path);
        LogUtils.trace(log, "doGet", url);
        if (queryParams != null && queryParams.size() > 0) {
            String qs = Utils.format(queryParams, "UTF-8");
            url += "?" + qs;
        }
        transferService.get(url, new StreamReceiver() {

            @Override
            public void receive(InputStream in) throws IOException {
                IOUtils.copy(in, out);
            }
        }, null, null);
    }

    /**
     *
     * @param path - encoded path, but not fully qualified. Must not be prefixed
     * with a slash, as it will be appended to the host's URL
     * @throws java.net.ConnectException
     * @throws Unauthorized
     * @throws UnknownHostException
     * @throws SocketTimeoutException
     * @throws IOException
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized void options(String path) throws java.net.ConnectException, NotAuthorizedException, UnknownHostException, SocketTimeoutException, IOException, com.ettrema.httpclient.HttpException, NotFoundException {
        String url = this.encodedUrl() + path;
        doOptions(url);
    }

    public void doOptions(Path path) throws NotFoundException, java.net.ConnectException, NotAuthorizedException, java.net.UnknownHostException, SocketTimeoutException, IOException, com.ettrema.httpclient.HttpException {
        String dest = buildEncodedUrl(path);
        doOptions(dest);
    }

    private synchronized void doOptions(String url) throws NotFoundException, java.net.ConnectException, NotAuthorizedException, java.net.UnknownHostException, SocketTimeoutException, IOException, com.ettrema.httpclient.HttpException {
        notifyStartRequest();
        String uri = url;
        log.trace("doOptions: {}", url);
        HttpOptions m = new HttpOptions(uri);
        InputStream in = null;
        try {
            int res = Utils.executeHttpWithStatus(client, m, null);
            log.trace("result code: " + res);
            if (res == 301 || res == 302) {
                return;
            }
            Utils.processResultCode(res, url);
        } catch (ConflictException ex) {
            throw new RuntimeException(ex);
        } catch (BadRequestException ex) {
            throw new RuntimeException(ex);
        } finally {
            Utils.close(in);
            notifyFinishRequest();
        }
    }

    /**
     * GET the contents of the given path. The path is non-encoded, and it relative
     * to the host's root.
     * 
     * @param path
     * @return
     * @throws com.ettrema.httpclient.HttpException
     * @throws NotAuthorizedException
     * @throws BadRequestException
     * @throws ConflictException
     * @throws NotFoundException 
     */
    public synchronized byte[] get(Path path) throws com.ettrema.httpclient.HttpException, NotAuthorizedException, BadRequestException, ConflictException, NotFoundException {
        Path root = Path.path(rootPath);
        Path p = root.add(path);
        String url = buildEncodedUrl(p);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            transferService.get(url, new StreamReceiver() {

                @Override
                public void receive(InputStream in) {
                    try {
                        IOUtils.copy(in, out);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }, null, null);
        } catch (CancelledException ex) {
            throw new RuntimeException("Should never happen because no progress listener is set", ex);
        }
        return out.toByteArray();        
    }
    
    /**
     * Retrieve the bytes at the specified path.
     *
     * @param path - encoded and relative to host's rootPath. Must NOT be slash prefixed
     * as it will be appended to the host's url
     * @return
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized byte[] get(String path) throws com.ettrema.httpclient.HttpException, NotAuthorizedException, BadRequestException, ConflictException, NotFoundException {
        String url = this.encodedUrl() + path;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            transferService.get(url, new StreamReceiver() {

                @Override
                public void receive(InputStream in) {
                    try {
                        IOUtils.copy(in, out);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }, null, null);
        } catch (CancelledException ex) {
            throw new RuntimeException("Should never happen because no progress listener is set", ex);
        }
        return out.toByteArray();
    }

    /**
     * POSTs the variables and returns the body
     *
     * @param url - fully qualified and encoded URL to post to
     * @param params
     * @return
     */
    public String doPost(String url, Map<String, String> params) throws com.ettrema.httpclient.HttpException, NotAuthorizedException, ConflictException, BadRequestException, NotFoundException {
        notifyStartRequest();
        HttpPost m = new HttpPost(url);
        List<NameValuePair> formparams = new ArrayList<NameValuePair>();
        for (Entry<String, String> entry : params.entrySet()) {
            formparams.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
        }
        UrlEncodedFormEntity entity;
        try {
            entity = new UrlEncodedFormEntity(formparams);
        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException(ex);
        }
        m.setEntity(entity);
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            int res = Utils.executeHttpWithStatus(client, m, bout);
            Utils.processResultCode(res, url);
            return bout.toString();
        } catch (HttpException ex) {
            throw new RuntimeException(ex);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            notifyFinishRequest();
        }
    }

    /**
     *
     * @param url - fully qualified and encoded
     * @param params
     * @param parts
     * @return
     * @throws com.ettrema.httpclient.HttpException
     */
//    public String doPost(String url, Map<String, String> params, Part[] parts) throws com.ettrema.httpclient.HttpException, NotAuthorizedException, ConflictException, BadRequestException, NotFoundException {
//        notifyStartRequest();
//        PostMethod filePost = new PostMethod(url);
//        if (params != null) {
//            for (Entry<String, String> entry : params.entrySet()) {
//                filePost.addParameter(entry.getKey(), entry.getValue());
//            }
//        }
//        filePost.setRequestEntity(new MultipartRequestEntity(parts, filePost.getParams()));
//
//        InputStream in = null;
//        try {
//            int res = client.executeMethod(filePost);
//            Utils.processResultCode(res, url);
//            in = filePost.getResponseBodyAsStream();
//            ByteArrayOutputStream bout = new ByteArrayOutputStream();
//            IOUtils.copy(in, bout);
//            return bout.toString();
//        } catch (HttpException ex) {
//            throw new RuntimeException(ex);
//        } catch (IOException ex) {
//            throw new RuntimeException(ex);
//        } finally {
//            Utils.close(in);
//            filePost.releaseConnection();
//            notifyFinishRequest();
//        }
//    }
    @Override
    public Host host() {
        return this;
    }

    @Override
    public String href() {
        String s = "http";
        int defaultPort = 80;
        if (secure) {
            s += "s";
            defaultPort = 443;
        }
        s += "://" + server;
        if (this.port != null && this.port != defaultPort && this.port > 0) {
            s += ":" + this.port;
        }

        if (rootPath != null && rootPath.length() > 0) {
            if (!rootPath.startsWith("/")) {
                s += "/";
            }
            s = s + rootPath;
        } else {
            s += "/";
        }
        if (!s.endsWith("/")) {
            s += "/";
        }
        return s;
    }

    /**
     * Returns the fully qualified URL for the given path
     *
     * @param path
     * @return
     */
    public String getHref(Path path) {
        String s = href();

        if (!path.isRelative()) {
            s = s.substring(0, s.length() - 1);
        }
        //log.trace("host href: " + s);
        return s + path; // path will be absolute
    }

    @Override
    public String encodedUrl() {
        return href(); // for a Host, there are no un-encoded components (eg rootPath, if present, must be encoded)
    }


    public com.ettrema.httpclient.Folder getOrCreateFolder(Path remoteParentPath, boolean create) throws com.ettrema.httpclient.HttpException, IOException, NotAuthorizedException, ConflictException, BadRequestException, NotFoundException {
        log.trace("getOrCreateFolder: {}", remoteParentPath);
        com.ettrema.httpclient.Folder f = this;
        if (remoteParentPath != null) {
            for (String childName : remoteParentPath.getParts()) {
                if (childName.equals("_code")) {
                    f = new Folder(f, childName, cache);
                } else {
                    com.ettrema.httpclient.Resource child = f.child(childName);
                    if (child == null) {
                        if (create) {
                            f = f.createFolder(childName);
                        } else {
                            return null;
                        }
                    } else if (child instanceof com.ettrema.httpclient.Folder) {
                        f = (com.ettrema.httpclient.Folder) child;
                    } else {
                        log.warn("Can't upload. A resource exists with the same name as a folder, but is a file: " + remoteParentPath + " - " + child.getClass());
                        return null;
                    }
                }

            }
        }
        return f;
    }

    /**
     * @return the timeout
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * @param timeout the timeout to set
     */
    public void setTimeout(int timeout) {
        this.timeout = timeout;
        transferService.setTimeout(timeout);
    }

    private void notifyStartRequest() {
        for (ConnectionListener l : connectionListeners) {
            l.onStartRequest();
        }
    }

    private void notifyFinishRequest() {
        for (ConnectionListener l : connectionListeners) {
            l.onFinishRequest();
        }
    }

    public void addConnectionListener(ConnectionListener e) {
        connectionListeners.add(e);
    }

    public String buildEncodedUrl(Path path) {
        String url = this.encodedUrl();
        String[] arr = path.getParts();
        for (int i = 0; i < arr.length; i++) {
            String s = arr[i];
            if (i > 0) {
                url += "/";
            }
            url += com.bradmcevoy.http.Utils.percentEncode(s);
        }
        return url;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public HttpClient getClient() {
        return client;
    }

    /**
     * TODO: should optimise so it only generates once per set of fields
     * 
     * @param fields
     * @return 
     */
    private String buildPropFindXml(List<QName> fields) {
        try {
            if( fields == null ) {
                fields = defaultFields;
            }
            Element elPropfind = new Element("propfind", RespUtils.NS_DAV);
            Document doc = new Document(elPropfind);        
            Element elProp = new Element("prop", RespUtils.NS_DAV);
            elPropfind.addContent(elProp);
            for( QName qn : fields ) {
                Element elName = new Element(qn.getLocalPart(), qn.getPrefix(), qn.getNamespaceURI());
                elProp.addContent(elName);
            }
            XMLOutputter outputter = new XMLOutputter();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            outputter.output(doc, out);
            return out.toString("UTF-8");
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
    
            
            
    static class PreemptiveAuthInterceptor implements HttpRequestInterceptor {

        @Override
        public void process(final HttpRequest request, final HttpContext context) {
            AuthState authState = (AuthState) context.getAttribute(ClientContext.TARGET_AUTH_STATE);

            // If no auth scheme avaialble yet, try to initialize it
            // preemptively
            if (authState.getAuthScheme() == null) {
                AuthScheme authScheme = (AuthScheme) context.getAttribute("preemptive-auth");
                if (authScheme != null) {
                    CredentialsProvider credsProvider = (CredentialsProvider) context.getAttribute(ClientContext.CREDS_PROVIDER);
                    HttpHost targetHost = (HttpHost) context.getAttribute(ExecutionContext.HTTP_TARGET_HOST);
                    Credentials creds = credsProvider.getCredentials(new AuthScope(targetHost.getHostName(), targetHost.getPort()));
                    if (creds == null) {
                        throw new RuntimeException("No credentials for preemptive authentication");
                    }
                    authState.setAuthScheme(authScheme);
                    authState.setCredentials(creds);
                }
            }

        }
    }

    static class NoRetryHttpRequestRetryHandler implements HttpRequestRetryHandler {

        @Override
        public boolean retryRequest(IOException exception, int executionCount, HttpContext context) {
            return false;
        }
    }

    static class MyDefaultHttpClient extends DefaultHttpClient {

        @Override
        protected HttpRequestRetryHandler createHttpRequestRetryHandler() {
            return new NoRetryHttpRequestRetryHandler();
        }

        @Override
        protected RequestDirector createClientRequestDirector(HttpRequestExecutor requestExec, ClientConnectionManager conman, ConnectionReuseStrategy reustrat, ConnectionKeepAliveStrategy kastrat, HttpRoutePlanner rouplan, HttpProcessor httpProcessor, HttpRequestRetryHandler retryHandler, RedirectStrategy redirectStrategy, AuthenticationHandler targetAuthHandler, AuthenticationHandler proxyAuthHandler, UserTokenHandler stateHandler, HttpParams params) {
            RequestDirector rd = super.createClientRequestDirector(requestExec, conman, reustrat, kastrat, rouplan, httpProcessor, retryHandler, redirectStrategy, targetAuthHandler, proxyAuthHandler, stateHandler, params);
            return rd;
        }
    }
}
