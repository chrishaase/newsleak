/*
 * Copyright 2016 Technische Universitaet Darmstadt
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

define([
    'angular'
], function (angular) {
    'use strict';

    angular.module('myApp.search', [])
        .controller('SearchController',
            [
                '$scope',
                '$window',
                'ObserverService',
                function ($scope,
                          $window,
                          ObserverService) {

                    $scope.observer = ObserverService;

                    $scope.addFulltext = function(input) {
                        if(input.length > 2) {
                            $scope.observer.addItem({
                                type: 'fulltext',
                                data: {
                                    id: -1,
                                    name: angular.copy(input),
                                    view: 'search'
                                }
                            });
                            $scope.fulltextInput = "";
                        }
                    };
                }
            ]);

});
