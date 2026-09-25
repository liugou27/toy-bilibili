package com.toys.video.common.api;

import java.util.List;

public record PageResult<T>(List<T> list, long total, long page, long size) {
}
