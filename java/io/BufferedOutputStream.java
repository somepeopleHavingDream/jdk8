/*
 * Copyright (c) 1994, 2003, Oracle and/or its affiliates. All rights reserved.
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

package java.io;

/**
 * The class implements a buffered output stream. By setting up such
 * an output stream, an application can write bytes to the underlying
 * output stream without necessarily causing a call to the underlying
 * system for each byte written.
 *
 * @author  Arthur van Hoff
 * @since   JDK1.0
 */
public
class BufferedOutputStream extends FilterOutputStream {
    /**
     * The internal buffer where data is stored.
     *
     * 数据被存储的内部缓冲区
     */
    protected byte buf[];

    /**
     * The number of valid bytes in the buffer. This value is always
     * in the range <tt>0</tt> through <tt>buf.length</tt>; elements
     * <tt>buf[0]</tt> through <tt>buf[count-1]</tt> contain valid
     * byte data.
     *
     * 此缓冲区内有效字节的个数。
     * 此值总是在0到buf.length之间；
     * 区间buf[0]到buf[count-1]的元素包含了有效字节数据。
     */
    protected int count;

    /**
     * Creates a new buffered output stream to write data to the
     * specified underlying output stream.
     *
     * @param   out   the underlying output stream.
     */
    public BufferedOutputStream(OutputStream out) {
        // 缓冲区大小设置为8192，即8k
        this(out, 8192);
    }

    /**
     * Creates a new buffered output stream to write data to the
     * specified underlying output stream with the specified buffer
     * size.
     *
     * @param   out    the underlying output stream.
     * @param   size   the buffer size.
     * @exception IllegalArgumentException if size &lt;= 0.
     */
    public BufferedOutputStream(OutputStream out, int size) {
        // 设置输出流
        super(out);

        // 如果缓冲区大小为负数，则抛出违规参数异常
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }

        // 生成新的缓冲区
        buf = new byte[size];
    }

    /** Flush the internal buffer */
    // 冲刷内部的缓冲区
    private void flushBuffer() throws IOException {
        // 如果缓冲区中存在有效数据，则将缓冲区内的所有有效数据写入，然后置有效字节数置为0
        if (count > 0) {
            out.write(buf, 0, count);
            count = 0;
        }
    }

    /**
     * Writes the specified byte to this buffered output stream.
     *
     * @param      b   the byte to be written.
     * @exception  IOException  if an I/O error occurs.
     */
    public synchronized void write(int b) throws IOException {
        if (count >= buf.length) {
            flushBuffer();
        }
        buf[count++] = (byte)b;
    }

    /**
     * Writes <code>len</code> bytes from the specified byte array
     * starting at offset <code>off</code> to this buffered output stream.
     *
     * <p> Ordinarily this method stores bytes from the given array into this
     * stream's buffer, flushing the buffer to the underlying output stream as
     * needed.  If the requested length is at least as large as this stream's
     * buffer, however, then this method will flush the buffer and write the
     * bytes directly to the underlying output stream.  Thus redundant
     * <code>BufferedOutputStream</code>s will not copy data unnecessarily.
     *
     * @param      b     the data.
     * @param      off   the start offset in the data.
     * @param      len   the number of bytes to write.
     * @exception  IOException  if an I/O error occurs.
     */
    public synchronized void write(byte b[], int off, int len) throws IOException {
        // 如果需要写入的长度大于或等于缓冲区的长度
        if (len >= buf.length) {
            /* If the request length exceeds the size of the output buffer,
               flush the output buffer and then write the data directly.
               In this way buffered streams will cascade harmlessly. */
            /*
                如果请求长度超过输出缓冲区的大小，则冲刷输出流缓冲区，然后直接输入数据。
                在这种方式下，缓冲流将无损级联。
             */
            // 冲刷缓冲区，先把缓冲区中的存留数据写入
            flushBuffer();
            // 再直接通过输出流写入，最后返回
            out.write(b, off, len);
            return;
        }

        // 如果需要写入的长度小于缓冲区总长度，但是大于缓冲区的剩余长度，则冲刷缓冲区
        if (len > buf.length - count) {
            flushBuffer();
        }

        // 将需要写入的数据先写入到缓冲区中，增加缓冲区的有效字节数
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    /**
     * Flushes this buffered output stream. This forces any buffered
     * output bytes to be written out to the underlying output stream.
     *
     * @exception  IOException  if an I/O error occurs.
     * @see        java.io.FilterOutputStream#out
     */
    public synchronized void flush() throws IOException {
        flushBuffer();
        out.flush();
    }
}
