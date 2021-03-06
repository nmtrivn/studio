/*
    Copyright 2019 Samsung SDS
    
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at
    
        http://www.apache.org/licenses/LICENSE-2.0
    
    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package com.samsung.sds.brightics.server.model.entity.repository;

import java.io.Serializable;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.PagingAndSortingRepository;

import com.samsung.sds.brightics.common.core.exception.BrighticsCoreException;

@NoRepositoryBean
public interface BrtcRepository<T, ID extends Serializable> extends PagingAndSortingRepository<T, ID> {
	Logger LOGGER = LoggerFactory.getLogger(BrtcRepository.class);

	default <S extends T> S save(S paramS, String serviceName) {
		try {
			S saveResult = save(paramS);
			if(saveResult == null){
				throw new BrighticsCoreException("3004", serviceName);
			}
			return saveResult;
		} catch (Exception e){
			LOGGER.error("save error", e);
			throw new BrighticsCoreException("3004", serviceName);
		}
	}

	default <S extends T> S update(S paramS, String serviceName) {
		try {
			S updateResult = save(paramS);
			if(updateResult == null){
				throw new BrighticsCoreException("3005", serviceName);
			}
			return updateResult;
		} catch (Exception e){
			LOGGER.error("update error", e);
			throw new BrighticsCoreException("3005", serviceName);
		}
	}

	default void delete(ID paramID, String serviceName) {
		try {
			deleteById(paramID);
		} catch (Exception e) {
			LOGGER.error("delete error", e);
			throw new BrighticsCoreException("3006", serviceName);
		}
	}

	default void delete(ID paramID) {
		try {
			deleteById(paramID);
		} catch (Exception e) {
			LOGGER.error("delete error", e);
			throw new BrighticsCoreException("3006", paramID.toString());
		}
	}
	
	default T findOne(ID paramID) {
		Optional<T> opt = findById(paramID);
		if(!opt.isPresent()) {
			return null;
		}else {
			return opt.get();
		}
	}
	
	default boolean exists(ID paramID) {
		return existsById(paramID);
	}
}
