package com.example.configmgr.ai.form;

import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ErrorCodes;

/** 已注册选项源读取失败；与父值无匹配或真实空选项区分。 */
public class FormOptionSourceUnavailableException extends CodedIllegalStateException {

	public FormOptionSourceUnavailableException(String sourceId, Throwable cause) {
		super(ErrorCodes.FORM_OPTION_SOURCE_UNAVAILABLE, "Form option source read failed sourceId=" + sourceId, cause);
	}
}
