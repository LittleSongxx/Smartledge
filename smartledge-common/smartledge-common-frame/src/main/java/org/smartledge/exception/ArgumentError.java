package org.smartledge.exception;

import lombok.Data;

/**
 * @description: 异常类
 * @author: Song
 **/

@Data
public class ArgumentError {

	private String argumentName;

	private String message;
}
