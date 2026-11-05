package com.onedata.portal.dto.dolphin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;


/**
 * Generic DolphinScheduler API response wrapper.
 * DS API returns responses in format: { code: int, msg: string, data: T }
 * where code=0 indicates success.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DolphinApiResponse<T> {

    /** Response code: 0 = success, other values indicate error */
    private int code;

    /** Response message */
    private String msg;

    /** Response data payload */
    private T data;

    /** Check if the request was successful */
    public boolean isSuccess() {
        return code == 0;
    }

    /** Get error message for failed requests */
    public String getErrorMessage() {
        return String.format("DolphinScheduler API error (code=%d): %s", code, msg);
    }
}
