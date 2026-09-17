package org.smartledge.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @description: 支撑组件
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoredObjectInfo {

    private String bucketName;

    private String objectName;

    private String objectUrl;
}
