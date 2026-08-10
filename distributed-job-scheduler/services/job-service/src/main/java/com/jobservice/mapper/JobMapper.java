package com.jobservice.mapper;

import com.jobservice.dto.request.CreateJobRequest;
import com.jobservice.dto.response.JobResponse;
import com.jobservice.entity.Job;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface JobMapper {

    JobResponse toResponse(Job job);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "status", ignore = true)
    Job toEntity(CreateJobRequest request);

}
