package cn.bulletjet.httpd.request;

import cn.bulletjet.httpd.exception.NotSupportException;
import cn.bulletjet.httpd.fileupload.RequestFileManager;
import cn.bulletjet.httpd.utils.Utils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Optional;

/**
 * 用于解析Http请求
 *
 * @author Bullet
 * @time 2017-05-30 23:20
 */
public class RequestResolver {

  private Request request;    // 将要生成的请求对象
  public static String SEPARATOR = "\r\n";

  /**
   * 从请求行中提取请求信息
   */
  private boolean resolverLine(String requestLine) throws UnsupportedEncodingException {
    String[] lineArgs = requestLine.split(" ");
    request.method = Request.Method.getMethodByName(lineArgs[0]);
    String pathAndQuery = URLDecoder.decode(lineArgs[1], "UTF-8");
    if (pathAndQuery.contains("?")) {
      String[] pathAndQuerySplit = pathAndQuery.split("\\?");
      request.path = pathAndQuerySplit[0];
      request.queryString = pathAndQuerySplit[1];
    } else {
      request.path = pathAndQuery;
      request.queryString = "";
    }
    // 当queryString不是空字符串时，从queryString中提取参数信息，放入paras中
    if (!request.queryString.equals("")) {
      String[] paraPairs = request.queryString.split("&");
      for (String paraPair : paraPairs) {
        String[] keyAndValue = paraPair.split("=");
        request.paras.put(keyAndValue[0], keyAndValue[1]);
      }
    }

    request.version = lineArgs[2];
    return true;
  }

  /**
   * 从给定的字符串中生成一个请求头
   *
   * @param requestHead 给定的字符串
   * @return 解析成功返回true，失败返回false
   */
  private boolean resolverHead(String requestHead) {
    // 将请求信息添加进head对象中
    for (String line : requestHead.split(RequestResolver.SEPARATOR)) {
      String[] kv = line.split(": ");
      request.headers.put(kv[0].toLowerCase(), kv[1].trim());        // HTTP HEAD不区分大小写，此处使用小写
    }
    // 生成Url地址
    request.url = request.headers.get("Host".toLowerCase()) + request.path
        + (request.queryString.equals("") ? "" : ("?" + request.queryString));

    // 处理cookies
    if (request.headers.get("Cookie".toLowerCase()) != null) {
      String[] cookies = request.headers.get("Cookie".toLowerCase()).split("; ");
      for (String cookie : cookies) {
        int splitIndex = cookie.indexOf("=");
        if (splitIndex
            == -1) {                                      // 找不到分割符号=，cookie不合法，则不处理这条cookie
          continue;
        } else {
          String key = cookie.substring(0, splitIndex);
          String value = cookie.substring(splitIndex + 1, cookie.length());
          request.cookies.set(key, value);
        }
      }
    }

    return true;
  }


  /**
   * 从输入流中解析请求体,仅在方法支持时进行调用，用于解析请求体
   *
   * @return 如果该请求类型实现了内置解析，解析并返回true，否则由用户自己实现解析并返回false
   * @param requestBody 输入的报文主体的内容
   */
  private boolean resolverBody(byte[] requestBody) throws NotSupportException, IOException {
    request.body.get().requestBody = requestBody;               // 将全部请求体拷贝进requestBody
    String contentTypeLine = request.headers.get("Content-Type".toLowerCase());
    String[] contentTypeArgs = contentTypeLine.split(";");
    request.body.get().contentType = contentTypeArgs[0].trim();

    if (request.body.get().contentType.equals(request.body.get().CONTENT_TYPE_URLENCODED)) {
      // 填充charset字段
      for (String arg : contentTypeArgs) {
        if (arg.trim().toLowerCase().startsWith("CharSet".toLowerCase())) {
          request.body.get().charset = Optional.of(arg.trim().toLowerCase().split("=")[1]);
        }
      }
      // 提取请求参数填充到paras中
      String parasString = URLDecoder
          .decode(new String(requestBody), request.body.get().charset.get());
      String[] paraPairs = parasString.split("&");
      for (String paraPair : paraPairs) {
        String[] keyAndValue = paraPair.split("=");
        request.paras.put(keyAndValue[0], keyAndValue[1]);
      }
      return true;
    } else if (request.body.get().contentType.equals(request.body.get().CONTENT_TYPE_MULTI_FORM)) {
      // 填充boundary字段
      for (String arg : contentTypeArgs) {
        if (arg.trim().toLowerCase().startsWith("Boundary".toLowerCase())) {
          request.body.get().boundary = Optional.of(arg.trim().split("=")[1]);
        }
      }
      // 提取请求参数
      String boundarySeparation = "--" + request.body.get().boundary.get();
      String boundaryEnd = "--" + request.body.get().boundary.get() + "--";
      if (Utils.findFirst(requestBody, boundaryEnd.getBytes()) == -1) {
        throw new NotSupportException("找不到请求体多表单提交的边界结束符");
      } else {
        byte[][] originalParts = Utils.split(requestBody, boundarySeparation.getBytes());
        // 去除第一个空bytes和最后一个无用的bytes，并去除剩余byte中首尾无用的\r\n
        byte[][] parts = new byte[originalParts.length - 2][];
        for (int i = 0; i < parts.length; i++) {
          parts[i] = Arrays.copyOfRange(originalParts[i + 1], 2, originalParts[i + 1].length - 2);
        }
        // 普通的不带filename的表单添加到paras中，文件表单统一使用二进制流进行保存
        for (byte[] part : parts) {
          String paraName = "undefined";
          String paraValue = "undefined";
          String filename = "undefined";
          boolean isFileUpload = false;
          byte[][] partHeadAndBody = Utils.split(part, (SEPARATOR + SEPARATOR).getBytes());
          String head = new String(partHeadAndBody[0]);
          byte[] partBody = partHeadAndBody[1];
          String[] partHeadLines = head.split(SEPARATOR);
          for (String partHeadLine : partHeadLines) {
            String[] keyAndValue = partHeadLine.split(": ");
            String keyOfLine = keyAndValue[0].trim();
            String valueOfLine = keyAndValue[1].trim();
            // 找到Content-Disposition，如果其对应的value中没有filename字段，就当作普通表单数据保存到paras中
            if (keyOfLine.equals("Content-Disposition")) {
              // 提取出键值对参数
              if (valueOfLine.startsWith("form-data;")) {
                String[] partLineParas = valueOfLine
                    .substring("form-data; ".length(), valueOfLine.length()).trim().split("; ");
                // 再次切分，形成键值对数组
                for (String partLinePara : partLineParas) {
                  String[] keyValuePairPartLinePara = partLinePara.split("=");
                  if (keyValuePairPartLinePara[0].equals("name")) {
                    paraName = keyValuePairPartLinePara[1]
                        .substring(1, keyValuePairPartLinePara[1].length() - 1);     // 去掉收尾的""
                  }
                  if (keyValuePairPartLinePara[0].equals("filename")) {
                    isFileUpload = true;
                    filename = keyValuePairPartLinePara[1]
                        .substring(1, keyValuePairPartLinePara[1].length() - 1);     // 去掉收尾的""
                  }
                }
              }
            }
          }
          if (!isFileUpload) {                      // 普通数据提交
            paraValue = new String(partHeadAndBody[1]);
            request.paras.put(paraName, paraValue);
          } else {                                  // 文件上传提交
            String savedFileName = request.uploadFileManager
                .save(paraName, filename, partHeadAndBody[1]);
            request.paras.put(paraName, savedFileName);
          }
        }
      }
      return true;
    } else {                  // 未实现的请求文本类型（Content-Type），什么都不做，由用户进行解析
      return true;
    }

  }

  private ByteArrayOutputStream GetRequestBody(byte[] startBytes, InputStream in, int bodyLength)
      throws IOException, NotSupportException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    baos.write(startBytes);
    byte[] bytesBuffer = new byte[1024];
    int remain = bodyLength - startBytes.length;
    while (remain > 0) {
      int length = in.read(bytesBuffer);
      if (length == -1) {
        throw new NotSupportException("报文主体长度不足");
      }
      baos.write(bytesBuffer, 0, length);
      remain -= length;
    }
    return baos;
  }

  public Request resolver(InputStream in, RequestFileManager requestFileManager)
      throws IOException, NotSupportException {
    request = new Request(requestFileManager);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    // 提取请求行数据
    byte[] lineEndFlag = SEPARATOR.getBytes();
    int lineEndIndex = readInputStreamUntilFindByteArray(in, baos, lineEndFlag);
    byte[] byteLine = Arrays.copyOfRange(baos.toByteArray(), 0, lineEndIndex);
    byte[] byteLineRemain = Arrays
        .copyOfRange(baos.toByteArray(), lineEndIndex + lineEndFlag.length,
            baos.toByteArray().length);
    resolverLine(new String(byteLine));

    // 提取请求头信息
    baos.reset();
    baos.write(byteLineRemain);
    byte[] headEndFlag = (SEPARATOR + SEPARATOR).getBytes();
    int headEndIndex = readInputStreamUntilFindByteArray(in, baos, headEndFlag);
    byte[] byteHead = Arrays.copyOfRange(baos.toByteArray(), 0, headEndIndex);
    byte[] byteHeadRemain = Arrays
        .copyOfRange(baos.toByteArray(), headEndIndex + headEndFlag.length,
            baos.toByteArray().length);
    resolverHead(new String(byteHead));

    // 如果请求方法支持请求体
    if (request.method.isSupportBody()) {
      request.body = Optional.of(request.new Body());             // 填充Body对象
      // 获取报文长度
      String strLength;
      if ((strLength = request.headers.get("content-length")) == null) {
        throw new NotSupportException("报文请求头中不含有content-length子段!");
      }
      int bodyLength = Integer.parseInt(strLength);
      // 获取全部报文
      baos = GetRequestBody(byteHeadRemain, in, bodyLength);
      byte[] requestBody = baos.toByteArray();

      resolverBody(requestBody);
    }
    return request;
  }

  /**
   * 一直读取流写进字节数组输出流直到发现指定字节数组内容
   *
   * @param in 持续读取的输入流
   * @param baos 要写进的字节数组输出流
   * @param byteArray 要发现的字节数组的内容
   * @return 发现的字节数组的首字节偏移量, 如果读取完都没发现，则返回-1
   */
  private int readInputStreamUntilFindByteArray(InputStream in, ByteArrayOutputStream baos,
      byte[] byteArray) throws IOException {
    int index;
    // 如果当前流中可找到byteArray，则直接返回
    if ((index = Utils.findFirst(baos.toByteArray(), byteArray)) >= 0) {
      return index;
    }
    // 否则边读取边判断
    int length;
    byte[] bytesBuffer = new byte[1024];
    while ((length = in.read(bytesBuffer)) != -1) {
      baos.write(bytesBuffer, 0, length);
      if ((index = Utils.findFirst(baos.toByteArray(), byteArray)) >= 0) {
        return index;
      }
    }
    // 输入流读完，没有发现，则返回-1
    return -1;
  }

}
