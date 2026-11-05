package com.example.configmgr.perf.production;

import com.example.configmgr.perf.redis.Manifest;

interface ProductionScenario {

	String id();

	Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) throws Exception;

	void measure(ProductionSupport support, Manifest.Dataset dataset) throws Exception;

}
