/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/**
 * Join requests: asks the user connected to a connection to admit a newcomer,
 * or lets the newcomer end the current session.
 */
angular.module('guacJdbcJoinRequests', ['auth', 'client', 'navigation', 'notification', 'rest']);
angular.module('index').requires.push('guacJdbcJoinRequests');

/**
 * REST calls for join requests, under api/session/ext/DATA_SOURCE/join/.
 */
angular.module('guacJdbcJoinRequests').factory('joinRequestService', ['$injector',
        function joinRequestService($injector) {

    var authenticationService = $injector.get('authenticationService');

    var base = function base(dataSource) {
        return 'api/session/ext/' + encodeURIComponent(dataSource) + '/join/';
    };

    return {

        getStatus : function getStatus(dataSource, connectionId) {
            return authenticationService.request({
                method : 'GET',
                url    : base(dataSource) + 'status/' + encodeURIComponent(connectionId)
            });
        },

        createRequest : function createRequest(dataSource, connectionId) {
            return authenticationService.request({
                method : 'POST',
                url    : base(dataSource) + 'requests',
                data   : { connectionId : connectionId }
            });
        },

        getRequest : function getRequest(dataSource, id) {
            return authenticationService.request({
                method : 'GET',
                url    : base(dataSource) + 'requests/' + encodeURIComponent(id)
            });
        },

        cancelRequest : function cancelRequest(dataSource, id) {
            return authenticationService.request({
                method : 'DELETE',
                url    : base(dataSource) + 'requests/' + encodeURIComponent(id)
            });
        },

        getIncoming : function getIncoming(dataSource) {
            return authenticationService.request({
                method : 'GET',
                url    : base(dataSource) + 'incoming'
            });
        },

        decide : function decide(dataSource, id, decision, sharingProfile) {
            return authenticationService.request({
                method : 'POST',
                url    : base(dataSource) + 'incoming/' + encodeURIComponent(id),
                data   : { decision : decision, sharingProfile : sharingProfile }
            });
        },

        enforce : function enforce(dataSource, connectionId) {
            return authenticationService.request({
                method : 'POST',
                url    : base(dataSource) + 'enforce/' + encodeURIComponent(connectionId)
            });
        }

    };

}]);

angular.module('guacJdbcJoinRequests').run(['$injector', function joinRequests($injector) {

    var $interval          = $injector.get('$interval');
    var $location          = $injector.get('$location');
    var $rootScope         = $injector.get('$rootScope');
    var ClientIdentifier   = $injector.get('ClientIdentifier');
    var guacClientManager  = $injector.get('guacClientManager');
    var guacNotification   = $injector.get('guacNotification');
    var joinRequestService = $injector.get('joinRequestService');
    var ManagedClientState = $injector.get('ManagedClientState');

    var JDBC_DATA_SOURCES = ['postgresql', 'mysql', 'sqlserver'];
    var POLL_MS = 3000;

    // Client IDs whose next navigation skips the in-use check
    var cleared = {};

    var isNotFound = function isNotFound(error) {
        return !!error && (error.type === 'NOT_FOUND' || error.statusCode === 404);
    };

    // guacNotification ignores a new status while another is shown
    var show = function show(status) {
        guacNotification.showStatus(false);
        guacNotification.showStatus(status);
    };

    var connect = function connect(id) {
        guacNotification.showStatus(false);
        cleared[id] = true;
        $location.url('/client/' + id);
    };

    var enforce = function enforce(id, identifier) {
        joinRequestService.enforce(identifier.dataSource, identifier.id)
            .then(function enforced() { connect(id); }, guacNotification.SHOW_REQUEST_ERROR);
    };

    var waitForAdmission;

    var outcome = function outcome(id, identifier, textKey, canRetry) {
        var actions = [];
        if (canRetry)
            actions.push({ name : 'JOIN.ACTION_RETRY',
                callback : function retry() { waitForAdmission(id, identifier); } });
        actions.push({ name : 'JOIN.ACTION_ENFORCE', className : 'danger',
            callback : function forced() { enforce(id, identifier); } });
        actions.push({ name : 'JOIN.ACTION_CANCEL',
            callback : function cancel() { guacNotification.showStatus(false); } });
        show({ title : 'JOIN.DIALOG_HEADER_WAITING', text : { key : textKey }, actions : actions });
    };

    waitForAdmission = function waitForAdmission(id, identifier) {

        joinRequestService.createRequest(identifier.dataSource, identifier.id)
                .then(function requested(created) {

            var poll = null;
            var stopRouteWatch = null;
            var stop = function stop() {
                if (poll) {
                    $interval.cancel(poll);
                    poll = null;
                }
                if (stopRouteWatch) {
                    stopRouteWatch();
                    stopRouteWatch = null;
                }
            };

            show({
                title   : 'JOIN.DIALOG_HEADER_WAITING',
                text    : { key : 'JOIN.TEXT_WAITING' },
                actions : [
                    { name : 'JOIN.ACTION_ENFORCE', className : 'danger',
                        callback : function forced() { stop(); enforce(id, identifier); } },
                    { name : 'JOIN.ACTION_CANCEL',
                        callback : function cancelled() {
                            stop();
                            joinRequestService.cancelRequest(identifier.dataSource, created.id);
                            guacNotification.showStatus(false);
                        } }
                ]
            });

            stopRouteWatch = $rootScope.$on('$routeChangeSuccess', stop);

            poll = $interval(function check() {
                joinRequestService.getRequest(identifier.dataSource, created.id)
                        .then(function polled(result) {
                    if (result.status === 'ADMITTED') {
                        stop();
                        guacNotification.showStatus(false);
                        var sharedId = ClientIdentifier.toString({
                            dataSource : identifier.dataSource + '-shared',
                            type       : ClientIdentifier.Types.CONNECTION,
                            id         : result.shareKey
                        });
                        $location.path('/client/' + sharedId).search({ key : result.shareKey });
                    }
                    else if (result.status === 'DENIED') {
                        stop();
                        outcome(id, identifier, 'JOIN.TEXT_DENIED', false);
                    }
                    else if (result.status === 'FREE' || result.status === 'CANCELLED') {
                        stop();
                        connect(id);
                    }
                }, function failed(error) {
                    stop();
                    if (isNotFound(error))
                        outcome(id, identifier, 'JOIN.TEXT_EXPIRED', true);
                    else
                        connect(id);
                });
            }, POLL_MS);

        }, function notRequested() {
            // Free by now, or the mailbox is unavailable: never block connecting
            connect(id);
        });

    };

    // Newcomer: check before a connection is opened
    $rootScope.$on('$routeChangeStart', function checkInUse(event, next) {

        if (!next || !next.$$route || next.$$route.originalPath !== '/client/:id')
            return;

        var id = next.params.id;
        if (next.params.key || cleared[id]) {
            delete cleared[id];
            return;
        }

        var identifier = ClientIdentifier.fromString(id);
        if (identifier.type !== ClientIdentifier.Types.CONNECTION
                || JDBC_DATA_SOURCES.indexOf(identifier.dataSource) === -1
                || id in guacClientManager.getManagedClients())
            return;

        event.preventDefault();

        joinRequestService.getStatus(identifier.dataSource, identifier.id)
                .then(function checked(status) {
            if (status.state === 'inUse')
                waitForAdmission(id, identifier);
            else
                connect(id);
        }, function unchecked() {
            connect(id);
        });

    });

    // Connected user: answer requests to join
    var shownRequest = null;

    var prompt = function prompt(dataSource, request) {

        var status = null;
        var done = function done() {
            if (guacNotification.getStatus() === status)
                guacNotification.showStatus(false);
            shownRequest = null;
        };
        var decide = function decide(decision, profile) {
            joinRequestService.decide(dataSource, request.id, decision, profile)
                .then(done, function failed(error) {
                    done();
                    guacNotification.SHOW_REQUEST_ERROR(error);
                });
        };

        var actions = request.sharingOptions.map(function toAction(option) {
            return { name : option.name,
                callback : function admit() { decide('admit', option.identifier); } };
        });
        actions.push(request.sharingOptions.length
            ? { name : 'JOIN.ACTION_DENY', callback : function deny() { decide('deny'); } }
            : { name : 'JOIN.ACTION_OK', callback : function ok() { decide('deny'); } });

        status = {
            title   : 'JOIN.DIALOG_HEADER_REQUEST',
            text    : {
                key       : request.sharingOptions.length
                        ? 'JOIN.TEXT_REQUEST' : 'JOIN.TEXT_REQUEST_NO_SHARING',
                variables : { USERNAME : request.requester }
            },
            actions : actions
        };

        shownRequest = { id : request.id, status : status };
        guacNotification.showStatus(status);

    };

    $interval(function pollIncoming() {

        var dataSources = {};
        angular.forEach(guacClientManager.getManagedClients(), function collect(client, id) {
            if (client.clientState.connectionState !== ManagedClientState.ConnectionState.CONNECTED)
                return;
            var dataSource = ClientIdentifier.fromString(id).dataSource;
            if (JDBC_DATA_SOURCES.indexOf(dataSource) !== -1)
                dataSources[dataSource] = true;
        });

        angular.forEach(dataSources, function check(unused, dataSource) {
            joinRequestService.getIncoming(dataSource).then(function received(requests) {

                // Withdraw a prompt whose request is gone (cancelled, expired, answered elsewhere)
                if (shownRequest && !requests.some(function same(r) { return r.id === shownRequest.id; })) {
                    if (guacNotification.getStatus() === shownRequest.status)
                        guacNotification.showStatus(false);
                    shownRequest = null;
                }

                if (requests.length && !shownRequest && !guacNotification.getStatus())
                    prompt(dataSource, requests[0]);

            });
        });

    }, POLL_MS);

}]);
