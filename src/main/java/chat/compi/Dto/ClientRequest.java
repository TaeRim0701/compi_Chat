package chat.compi.Dto;

import java.io.Serializable;
import java.util.Map;

public class ClientRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum RequestType {
        LOGIN, REGISTER, LOGOUT,
        GET_FRIEND_LIST, ADD_FRIEND, REMOVE_FRIEND,
        CREATE_CHAT_ROOM, INVITE_USER_TO_ROOM, GET_CHAT_ROOMS, GET_MESSAGES_IN_ROOM,
        SEND_MESSAGE, READ_MESSAGE,
        GET_NOTICE_MESSAGES, GET_TIMELINE_EVENTS,
        UPLOAD_FILE, DOWNLOAD_FILE,
        SET_AWAY_STATUS, // 자리비움 상태 설정
        LEAVE_CHAT_ROOM
    }

    private RequestType type;
    private Map<String, Object> data;

    public ClientRequest(RequestType type, Map<String, Object> data) {
        this.type = type;
        this.data = data;
    }

    public RequestType getType() {
        return type;
    }

    public Map<String, Object> getData() {
        return data;
    }
}
