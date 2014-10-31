package com.arkasoft.webpresence.server;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet(name="x-dtalk", urlPatterns={"/x-dtalk.js"}) 
public class WebPresenceJSServlet extends HttpServlet {
  private static final long serialVersionUID = -8471221600802003818L;

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
    // read x-dtalk.js
    String script = readResource("/x-dtalk.js");

    if (script != null) {

      //
      // apply filters
      //

      String contextPath = req.getContextPath();
      if (!contextPath.startsWith("/")) {
        contextPath = "/" + contextPath;
      }
      
      String url;
      if (req.getProtocol().startsWith("https")) {
        url = "wss://";
      } else {
        url = "ws://";
      }
      url += req.getServerName() + ":" + req.getServerPort() + contextPath;
      
      script = script.replace("@UUID@", UUID.randomUUID().toString());
      script = script.replace("@WEB_PRESENCE_SRV@", url + "/dtalksrv");
      
      //
      // The End!
      //
      
      res.setContentType("text/javascript");
      
      PrintWriter out = res.getWriter();
      out.println(script);
      out.flush();
      out.close();
    }
  }

  public static String readResource(String resource) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    InputStream in = null;
    try {
      InputStream resourceAsStream = loader.getResourceAsStream(resource);
      if (resourceAsStream == null) {
        throw new IllegalArgumentException("Could not find resource " + resource);
      }

      in = new BufferedInputStream(resourceAsStream);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      int b = -1;
      while ((b = in.read()) != -1) {
        out.write(b);
      }
      String resourceAsString = new String(out.toByteArray(), "UTF-8");
      return resourceAsString;
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      try {
        if (in != null) {
          in.close();
        }
      } catch (IOException nothingToDo) {
      }
    }
  }
}
