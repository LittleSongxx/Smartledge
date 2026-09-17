package com.baidu.fsg.uid.buffer;

/**
 * @description: 处理器
 * @author: Song
 **/

@FunctionalInterface
public interface RejectedPutBufferHandler {

    void rejectPutBuffer(RingBuffer ringBuffer, long uid);
}
