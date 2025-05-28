package chat.compi.Dto;

import java.io.Serializable;
import java.util.Map;

public class ServerResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum ResponseType {
        SUCCESS, FAIL,
        LOGIN_SUCCESS, REGISTER_SUCCESS,
        FRIEND_LIST_UPDATE, FRIEND_STATUS_UPDATE,
        CHAT_ROOMS_UPDATE, ROOM_MESSAGES_UPDATE,
        NEW_MESSAGE, MESSAGE_READ_CONFIRM,
        NOTICE_LIST_UPDATE, TIMELINE_UPDATE,
        FILE_UPLOAD_SUCCESS, FILE_DOWNLOAD_SUCCESS,
        SYSTEM_NOTIFICATION // 시스템 알림 (읽지 않은 메시지 등)
    }

    private ResponseType type;
    private boolean success;
    private String message;
    private Map<String, Object> data;

    public ServerResponse(ResponseType type, boolean success, String message, Map<String, Object> data) {
        this.type = type;
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public ResponseType getType() {
        return type;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getData() {
        return data;
    }
}