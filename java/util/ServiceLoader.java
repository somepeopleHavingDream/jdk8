/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package java.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.AccessController;
import java.security.AccessControlContext;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;


/**
 * A simple service-provider loading facility.
 *
 * <p> A <i>service</i> is a well-known set of interfaces and (usually
 * abstract) classes.  A <i>service provider</i> is a specific implementation
 * of a service.  The classes in a provider typically implement the interfaces
 * and subclass the classes defined in the service itself.  Service providers
 * can be installed in an implementation of the Java platform in the form of
 * extensions, that is, jar files placed into any of the usual extension
 * directories.  Providers can also be made available by adding them to the
 * application's class path or by some other platform-specific means.
 *
 * <p> For the purpose of loading, a service is represented by a single type,
 * that is, a single interface or abstract class.  (A concrete class can be
 * used, but this is not recommended.)  A provider of a given service contains
 * one or more concrete classes that extend this <i>service type</i> with data
 * and code specific to the provider.  The <i>provider class</i> is typically
 * not the entire provider itself but rather a proxy which contains enough
 * information to decide whether the provider is able to satisfy a particular
 * request together with code that can create the actual provider on demand.
 * The details of provider classes tend to be highly service-specific; no
 * single class or interface could possibly unify them, so no such type is
 * defined here.  The only requirement enforced by this facility is that
 * provider classes must have a zero-argument constructor so that they can be
 * instantiated during loading.
 *
 * <p><a name="format"> A service provider is identified by placing a
 * <i>provider-configuration file</i> in the resource directory
 * <tt>META-INF/services</tt>.</a>  The file's name is the fully-qualified <a
 * href="../lang/ClassLoader.html#name">binary name</a> of the service's type.
 * The file contains a list of fully-qualified binary names of concrete
 * provider classes, one per line.  Space and tab characters surrounding each
 * name, as well as blank lines, are ignored.  The comment character is
 * <tt>'#'</tt> (<tt>'&#92;u0023'</tt>,
 * <font style="font-size:smaller;">NUMBER SIGN</font>); on
 * each line all characters following the first comment character are ignored.
 * The file must be encoded in UTF-8.
 *
 * <p> If a particular concrete provider class is named in more than one
 * configuration file, or is named in the same configuration file more than
 * once, then the duplicates are ignored.  The configuration file naming a
 * particular provider need not be in the same jar file or other distribution
 * unit as the provider itself.  The provider must be accessible from the same
 * class loader that was initially queried to locate the configuration file;
 * note that this is not necessarily the class loader from which the file was
 * actually loaded.
 *
 * <p> Providers are located and instantiated lazily, that is, on demand.  A
 * service loader maintains a cache of the providers that have been loaded so
 * far.  Each invocation of the {@link #iterator iterator} method returns an
 * iterator that first yields all of the elements of the cache, in
 * instantiation order, and then lazily locates and instantiates any remaining
 * providers, adding each one to the cache in turn.  The cache can be cleared
 * via the {@link #reload reload} method.
 *
 * <p> Service loaders always execute in the security context of the caller.
 * Trusted system code should typically invoke the methods in this class, and
 * the methods of the iterators which they return, from within a privileged
 * security context.
 *
 * <p> Instances of this class are not safe for use by multiple concurrent
 * threads.
 *
 * <p> Unless otherwise specified, passing a <tt>null</tt> argument to any
 * method in this class will cause a {@link NullPointerException} to be thrown.
 *
 *
 * <p><span style="font-weight: bold; padding-right: 1em">Example</span>
 * Suppose we have a service type <tt>com.example.CodecSet</tt> which is
 * intended to represent sets of encoder/decoder pairs for some protocol.  In
 * this case it is an abstract class with two abstract methods:
 *
 * <blockquote><pre>
 * public abstract Encoder getEncoder(String encodingName);
 * public abstract Decoder getDecoder(String encodingName);</pre></blockquote>
 *
 * Each method returns an appropriate object or <tt>null</tt> if the provider
 * does not support the given encoding.  Typical providers support more than
 * one encoding.
 *
 * <p> If <tt>com.example.impl.StandardCodecs</tt> is an implementation of the
 * <tt>CodecSet</tt> service then its jar file also contains a file named
 *
 * <blockquote><pre>
 * META-INF/services/com.example.CodecSet</pre></blockquote>
 *
 * <p> This file contains the single line:
 *
 * <blockquote><pre>
 * com.example.impl.StandardCodecs    # Standard codecs</pre></blockquote>
 *
 * <p> The <tt>CodecSet</tt> class creates and saves a single service instance
 * at initialization:
 *
 * <blockquote><pre>
 * private static ServiceLoader&lt;CodecSet&gt; codecSetLoader
 *     = ServiceLoader.load(CodecSet.class);</pre></blockquote>
 *
 * <p> To locate an encoder for a given encoding name it defines a static
 * factory method which iterates through the known and available providers,
 * returning only when it has located a suitable encoder or has run out of
 * providers.
 *
 * <blockquote><pre>
 * public static Encoder getEncoder(String encodingName) {
 *     for (CodecSet cp : codecSetLoader) {
 *         Encoder enc = cp.getEncoder(encodingName);
 *         if (enc != null)
 *             return enc;
 *     }
 *     return null;
 * }</pre></blockquote>
 *
 * <p> A <tt>getDecoder</tt> method is defined similarly.
 *
 *
 * <p><span style="font-weight: bold; padding-right: 1em">Usage Note</span> If
 * the class path of a class loader that is used for provider loading includes
 * remote network URLs then those URLs will be dereferenced in the process of
 * searching for provider-configuration files.
 *
 * <p> This activity is normal, although it may cause puzzling entries to be
 * created in web-server logs.  If a web server is not configured correctly,
 * however, then this activity may cause the provider-loading algorithm to fail
 * spuriously.
 *
 * <p> A web server should return an HTTP 404 (Not Found) response when a
 * requested resource does not exist.  Sometimes, however, web servers are
 * erroneously configured to return an HTTP 200 (OK) response along with a
 * helpful HTML error page in such cases.  This will cause a {@link
 * ServiceConfigurationError} to be thrown when this class attempts to parse
 * the HTML page as a provider-configuration file.  The best solution to this
 * problem is to fix the misconfigured web server to return the correct
 * response code (HTTP 404) along with the HTML error page.
 *
 * @param  <S>
 *         The type of the service to be loaded by this loader
 *
 * @author Mark Reinhold
 * @since 1.6
 */

public final class ServiceLoader<S>
    implements Iterable<S>
{

    /**
     * 前缀
     */
    private static final String PREFIX = "META-INF/services/";

    // The class or interface representing the service being loaded
    private final Class<S> service;

    // The class loader used to locate, load, and instantiate providers
    private final ClassLoader loader;

    // The access control context taken when the ServiceLoader is created
    // 当服务加载器被创建，访问控制上下文被采用
    private final AccessControlContext acc;

    // Cached providers, in instantiation order
    // 以实例化顺序缓存提供者（服务名->服务实例）
    private LinkedHashMap<String,S> providers = new LinkedHashMap<>();

    // The current lazy-lookup iterator
    // 当前懒查找迭代器
    private LazyIterator lookupIterator;

    /**
     * Clear this loader's provider cache so that all providers will be
     * reloaded.
     *
     * <p> After invoking this method, subsequent invocations of the {@link
     * #iterator() iterator} method will lazily look up and instantiate
     * providers from scratch, just as is done by a newly-created loader.
     *
     * <p> This method is intended for use in situations in which new providers
     * can be installed into a running Java virtual machine.
     */
    public void reload() {
        // 清除提供者集合
        providers.clear();
        // 实例化一个懒加载迭代器，名为“查找迭代器”
        lookupIterator = new LazyIterator(service, loader);
    }

    private ServiceLoader(Class<S> svc, ClassLoader cl) {
        // 设置service、loader、acc成员变量
        service = Objects.requireNonNull(svc, "Service interface cannot be null");
        loader = (cl == null) ? ClassLoader.getSystemClassLoader() : cl;
        acc = (System.getSecurityManager() != null) ? AccessController.getContext() : null;

        // 重新加载
        reload();
    }

    private static void fail(Class<?> service, String msg, Throwable cause)
        throws ServiceConfigurationError
    {
        throw new ServiceConfigurationError(service.getName() + ": " + msg,
                                            cause);
    }

    private static void fail(Class<?> service, String msg)
        throws ServiceConfigurationError
    {
        throw new ServiceConfigurationError(service.getName() + ": " + msg);
    }

    private static void fail(Class<?> service, URL u, int line, String msg)
        throws ServiceConfigurationError
    {
        fail(service, u + ":" + line + ": " + msg);
    }

    // Parse a single line from the given configuration file, adding the name
    // on the line to the names list.
    //
    // 从给定配置文件中解析一行，将行内的名字添加到名称列表中
    private int parseLine(Class<?> service, URL u, BufferedReader r, int lc,
                          List<String> names)
        throws IOException, ServiceConfigurationError
    {
        // 读取一行，如果为空，则直接返回
        String ln = r.readLine();
        if (ln == null) {
            return -1;
        }

        // 只取每行#字符之前的内容，即只读取类名
        int ci = ln.indexOf('#');
        if (ci >= 0) ln = ln.substring(0, ci);
        ln = ln.trim();

        // 记录长度
        int n = ln.length();
        // 如果长度不为0
        if (n != 0) {
            // 如果截取的字符串中存在空格或制表符，则解析失败
            if ((ln.indexOf(' ') >= 0) || (ln.indexOf('\t') >= 0))
                fail(service, u, lc, "Illegal configuration-file syntax");

            // 得到截取字符串起始位置的码点
            int cp = ln.codePointAt(0);
            // 如果起始字符不是以Java标识符开始的，则解析失败
            if (!Character.isJavaIdentifierStart(cp))
                fail(service, u, lc, "Illegal provider-class name: " + ln);

            // 检查截取字符串中是否存在非Java标识符的部分，如果存在，则解析失败
            for (int i = Character.charCount(cp); i < n; i += Character.charCount(cp)) {
                cp = ln.codePointAt(i);
                if (!Character.isJavaIdentifierPart(cp) && (cp != '.'))
                    fail(service, u, lc, "Illegal provider-class name: " + ln);
            }

            // 如果提供者里不包含此提供者名，并且名称列表里也不包含此提供者名，则将此提供者名添加到名称列表中
            if (!providers.containsKey(ln) && !names.contains(ln))
                names.add(ln);
        }

        // 返回需要解析的下一行行号
        return lc + 1;
    }

    // Parse the content of the given URL as a provider-configuration file.
    // 将给定统一资源定位符的内容解析为提供者配置文件。
    //
    // @param  service
    //         The service type for which providers are being sought;
    //         used to construct error detail strings
    //
    // @param  u
    //         The URL naming the configuration file to be parsed
    //
    // @return A (possibly empty) iterator that will yield the provider-class
    //         names in the given configuration file that are not yet members
    //         of the returned set
    //
    // @throws ServiceConfigurationError
    //         If an I/O error occurs while reading from the given URL, or
    //         if a configuration-file format error is detected
    //
    private Iterator<String> parse(Class<?> service, URL u)
        throws ServiceConfigurationError
    {
        InputStream in = null;
        BufferedReader r = null;
        // 提供者名称集合
        ArrayList<String> names = new ArrayList<>();

        try {
            // 打开流
            in = u.openStream();
            // 将输入流转换为缓冲字符输入流
            r = new BufferedReader(new InputStreamReader(in, "utf-8"));

            // 逐行解析，直至解析完全部行
            int lc = 1;
            while ((lc = parseLine(service, u, r, lc, names)) >= 0);
        } catch (IOException x) {
            // 捕获并处理解析失败的IO异常
            fail(service, "Error reading configuration file", x);
        } finally {
            // 善后处理，关闭流
            try {
                if (r != null) r.close();
                if (in != null) in.close();
            } catch (IOException y) {
                fail(service, "Error closing configuration file", y);
            }
        }

        // 返回提供者名称迭代器
        return names.iterator();
    }

    // Private inner class implementing fully-lazy provider lookup
    // 实现完全懒提供者查找的私有内部类
    private class LazyIterator
        implements Iterator<S>
    {
        /**
         * 所需要的服务类引用
         */
        Class<S> service;

        /**
         * 类加载器
         */
        ClassLoader loader;

        /**
         * 统一资源定位符枚举实例
         */
        Enumeration<URL> configs = null;

        /**
         * 提供者名称迭代器
         */
        Iterator<String> pending = null;

        /**
         * 下一个服务名称
         */
        String nextName = null;

        private LazyIterator(Class<S> service, ClassLoader loader) {
            // 设置服务和类加载器
            this.service = service;
            this.loader = loader;
        }

        /**
         * 是否有下一个服务
         *
         * @return 是否有下一个服务
         */
        private boolean hasNextService() {
            // 如果下一个服务的名字不为空，则直接返回真
            if (nextName != null) {
                return true;
            }

            // 这部分代码的作用是获取统一资源定位符枚举实例
            // 如果统一资源定位符枚举实例为空
            if (configs == null) {
                try {
                    // 获取服务完整名称
                    String fullName = PREFIX + service.getName();

                    // 如果类加载器为空，则通过类加载器的公共方法获取统一资源定位符枚举实例
                    if (loader == null)
                        configs = ClassLoader.getSystemResources(fullName);
                    else
                        // 类加载器不为空，则通过该类加载器获取统一资源定位符实例
                        configs = loader.getResources(fullName);
                } catch (IOException x) {
                    // 如果捕获到了任何输入输出异常，则做失败处理
                    fail(service, "Error locating configuration files", x);
                }
            }

            // 如果待办迭代器为空或者待办迭代器没有下一个元素
            while ((pending == null) || !pending.hasNext()) {
                // 如果统一资源定位符枚举实例没有更多的元素，则直接返回假
                if (!configs.hasMoreElements()) {
                    return false;
                }

                // 否则，即存在下一个服务，则进行解析，获得所有提供者名称迭代器
                pending = parse(service, configs.nextElement());
            }

            // 设置下一个服务的名称，最后返回真
            nextName = pending.next();
            return true;
        }

        /**
         * 返回下一个服务
         *
         * @return 下一个服务
         */
        private S nextService() {
            // 如果没有下一个服务，则抛出异常
            if (!hasNextService())
                throw new NoSuchElementException();

            // 获得下一个服务的名称，并重置下一个服务引用
            String cn = nextName;
            nextName = null;

            Class<?> c = null;
            try {
                // 类加载服务类，但并不实例化
                c = Class.forName(cn, false, loader);
            } catch (ClassNotFoundException x) {
                // 捕获并处理类未被找到异常
                fail(service,
                     "Provider " + cn + " not found");
            }

            // 如果service与类加载出来的类变量不具备继承关系，则失败
            if (!service.isAssignableFrom(c)) {
                fail(service,
                     "Provider " + cn  + " not a subtype");
            }
            try {
                // 生成实例，强转为泛型实例
                S p = service.cast(c.newInstance());
                // 将服务名和服务实例放入到提供者中，做一个缓存
                providers.put(cn, p);

                // 返回服务实例
                return p;
            } catch (Throwable x) {
                // 捕获并处理可抛出异常
                fail(service,
                     "Provider " + cn + " could not be instantiated",
                     x);
            }

            // 抛出Error，但这不可能发生
            throw new Error();          // This cannot happen
        }

        public boolean hasNext() {
            // 如果访问控制器为空，则调用hasNextService方法，否则做其他处理
            if (acc == null) {
                return hasNextService();
            } else {
                PrivilegedAction<Boolean> action = new PrivilegedAction<Boolean>() {
                    public Boolean run() { return hasNextService(); }
                };
                return AccessController.doPrivileged(action, acc);
            }
        }

        public S next() {
            // 如果访问控制器为空，则调用nextService方法，否则做其他处理
            if (acc == null) {
                return nextService();
            } else {
                PrivilegedAction<S> action = new PrivilegedAction<S>() {
                    public S run() { return nextService(); }
                };
                return AccessController.doPrivileged(action, acc);
            }
        }

        /**
         * 不支持移除操作
         */
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

    /**
     * Lazily loads the available providers of this loader's service.
     *
     * <p> The iterator returned by this method first yields all of the
     * elements of the provider cache, in instantiation order.  It then lazily
     * loads and instantiates any remaining providers, adding each one to the
     * cache in turn.
     *
     * <p> To achieve laziness the actual work of parsing the available
     * provider-configuration files and instantiating providers must be done by
     * the iterator itself.  Its {@link java.util.Iterator#hasNext hasNext} and
     * {@link java.util.Iterator#next next} methods can therefore throw a
     * {@link ServiceConfigurationError} if a provider-configuration file
     * violates the specified format, or if it names a provider class that
     * cannot be found and instantiated, or if the result of instantiating the
     * class is not assignable to the service type, or if any other kind of
     * exception or error is thrown as the next provider is located and
     * instantiated.  To write robust code it is only necessary to catch {@link
     * ServiceConfigurationError} when using a service iterator.
     *
     * <p> If such an error is thrown then subsequent invocations of the
     * iterator will make a best effort to locate and instantiate the next
     * available provider, but in general such recovery cannot be guaranteed.
     *
     * <blockquote style="font-size: smaller; line-height: 1.2"><span
     * style="padding-right: 1em; font-weight: bold">Design Note</span>
     * Throwing an error in these cases may seem extreme.  The rationale for
     * this behavior is that a malformed provider-configuration file, like a
     * malformed class file, indicates a serious problem with the way the Java
     * virtual machine is configured or is being used.  As such it is
     * preferable to throw an error rather than try to recover or, even worse,
     * fail silently.</blockquote>
     *
     * <p> The iterator returned by this method does not support removal.
     * Invoking its {@link java.util.Iterator#remove() remove} method will
     * cause an {@link UnsupportedOperationException} to be thrown.
     *
     * @implNote When adding providers to the cache, the {@link #iterator
     * Iterator} processes resources in the order that the {@link
     * java.lang.ClassLoader#getResources(java.lang.String)
     * ClassLoader.getResources(String)} method finds the service configuration
     * files.
     *
     * @return  An iterator that lazily loads providers for this loader's
     *          service
     */
    public Iterator<S> iterator() {
        // 生成一个迭代器实例
        return new Iterator<S>() {

            // 获得服务提供者集合的迭代器
            Iterator<Map.Entry<String,S>> knownProviders
                = providers.entrySet().iterator();

            public boolean hasNext() {
                // 如果已知服务提供者迭代器有下一个提供者，则直接返回真
                if (knownProviders.hasNext())
                    return true;

                // 如果已知提供者迭代器没有下一个提供者，则找查找迭代器
                return lookupIterator.hasNext();
            }

            public S next() {
                // 如果在已知提供者迭代器里找到，则直接返回迭代器实例
                if (knownProviders.hasNext())
                    return knownProviders.next().getValue();

                // 否则，返回查找迭代器里的下一个实例
                return lookupIterator.next();
            }

            /**
             * 不支持删除操作
             */
            public void remove() {
                throw new UnsupportedOperationException();
            }

        };
    }

    /**
     * Creates a new service loader for the given service type and class
     * loader.
     *
     * @param  <S> the class of the service type
     *
     * @param  service
     *         The interface or abstract class representing the service
     *
     * @param  loader
     *         The class loader to be used to load provider-configuration files
     *         and provider classes, or <tt>null</tt> if the system class
     *         loader (or, failing that, the bootstrap class loader) is to be
     *         used
     *
     * @return A new service loader
     */
    public static <S> ServiceLoader<S> load(Class<S> service,
                                            ClassLoader loader)
    {
        // 生成并返回ServiceLoader实例
        return new ServiceLoader<>(service, loader);
    }

    /**
     * Creates a new service loader for the given service type, using the
     * current thread's {@linkplain java.lang.Thread#getContextClassLoader
     * context class loader}.
     *
     * <p> An invocation of this convenience method of the form
     *
     * <blockquote><pre>
     * ServiceLoader.load(<i>service</i>)</pre></blockquote>
     *
     * is equivalent to
     *
     * <blockquote><pre>
     * ServiceLoader.load(<i>service</i>,
     *                    Thread.currentThread().getContextClassLoader())</pre></blockquote>
     *
     * @param  <S> the class of the service type
     *
     * @param  service
     *         The interface or abstract class representing the service
     *
     * @return A new service loader
     */
    public static <S> ServiceLoader<S> load(Class<S> service) {
        // 获得线程上下文类加载器，将此类加载器作为入参传进去
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return ServiceLoader.load(service, cl);
    }

    /**
     * Creates a new service loader for the given service type, using the
     * extension class loader.
     *
     * <p> This convenience method simply locates the extension class loader,
     * call it <tt><i>extClassLoader</i></tt>, and then returns
     *
     * <blockquote><pre>
     * ServiceLoader.load(<i>service</i>, <i>extClassLoader</i>)</pre></blockquote>
     *
     * <p> If the extension class loader cannot be found then the system class
     * loader is used; if there is no system class loader then the bootstrap
     * class loader is used.
     *
     * <p> This method is intended for use when only installed providers are
     * desired.  The resulting service will only find and load providers that
     * have been installed into the current Java virtual machine; providers on
     * the application's class path will be ignored.
     *
     * @param  <S> the class of the service type
     *
     * @param  service
     *         The interface or abstract class representing the service
     *
     * @return A new service loader
     */
    public static <S> ServiceLoader<S> loadInstalled(Class<S> service) {
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        ClassLoader prev = null;
        while (cl != null) {
            prev = cl;
            cl = cl.getParent();
        }
        return ServiceLoader.load(service, prev);
    }

    /**
     * Returns a string describing this service.
     *
     * @return  A descriptive string
     */
    public String toString() {
        return "java.util.ServiceLoader[" + service.getName() + "]";
    }

}
