package dev.blockmate.protocol;

/** hub → ui：伙伴回复的流式分片；最后一片 done=true 并带存库后的 messageId。 */
public record Reply(String replyId, String inReplyTo, String chunk, boolean done, String messageId, String source) {

    /** 任务单 18 之前的写法：不是主动说的。 */
    public Reply(String replyId, String inReplyTo, String chunk, boolean done, String messageId) {
        this(replyId, inReplyTo, chunk, done, messageId, null);
    }

}
