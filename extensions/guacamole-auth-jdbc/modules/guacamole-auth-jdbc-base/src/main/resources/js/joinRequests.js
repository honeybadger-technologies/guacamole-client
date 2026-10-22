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

// Newcomer: hold a connection that another user is using until it is admitted
// or enforced. Gating connect(), not the route, keeps deep links and the
// single-connection home redirect rendering normally.
angular.module('guacJdbcJoinRequests').config(['$provide', function gateConnect($provide) {
    $provide.decorator('ManagedClient', ['$delegate', '$injector',
            function decorateManagedClient($delegate, $injector) {

        var originalConnect = $delegate.connect;

        $delegate.connect = function gatedConnect(managedClient, width, height) {
            return $injector.get('joinGate').connect(managedClient, width, height, originalConnect);
        };

        return $delegate;

    }]);
}]);

// A user whose only connection is held by someone else lands on the connection
// list instead of being sent straight into it, so they can still reach other
// pages, and cancelling a join request does not loop back into the prompt.
angular.module('guacJdbcJoinRequests').config(['$provide', function busyHomePage($provide) {
    $provide.decorator('userPageService', ['$delegate', '$injector',
            function decorateUserPageService($delegate, $injector) {

        var originalGetHomePage = $delegate.getHomePage;

        $delegate.getHomePage = function getHomePage() {

            var $q                 = $injector.get('$q');
            var ClientIdentifier   = $injector.get('ClientIdentifier');
            var PageDefinition     = $injector.get('PageDefinition');
            var joinRequestService = $injector.get('joinRequestService');

            return originalGetHomePage.apply(this, arguments).then(function homePage(page) {

                var match = /^\/client\/([^/?]+)/.exec(page.url || '');
                if (!match)
                    return page;

                var identifier = ClientIdentifier.fromString(decodeURIComponent(match[1]));
                if (identifier.type !== ClientIdentifier.Types.CONNECTION
                        || ['postgresql', 'mysql', 'sqlserver'].indexOf(identifier.dataSource) === -1)
                    return page;

                return joinRequestService.getStatus(identifier.dataSource, identifier.id)
                    .then(function checked(status) {
                        if (status.state !== 'inUse')
                            return page;
                        return new PageDefinition({ name : 'USER_MENU.ACTION_NAVIGATE_HOME', url : '/' });
                    }, function unchecked() {
                        return $q.resolve(page);
                    });

            });

        };

        return $delegate;

    }]);
}]);

angular.module('guacJdbcJoinRequests').factory('joinGate', ['$injector',
        function joinGate($injector) {

    var $interval             = $injector.get('$interval');
    var $location             = $injector.get('$location');
    var $rootScope            = $injector.get('$rootScope');
    var authenticationService = $injector.get('authenticationService');
    var ClientIdentifier   = $injector.get('ClientIdentifier');
    var guacClientManager  = $injector.get('guacClientManager');
    var guacNotification   = $injector.get('guacNotification');
    var joinRequestService = $injector.get('joinRequestService');
    var ManagedClientState = $injector.get('ManagedClientState');

    var JDBC_DATA_SOURCES = ['postgresql', 'mysql', 'sqlserver'];
    var POLL_MS = 3000;

    var isNotFound = function isNotFound(error) {
        return !!error && (error.type === 'NOT_FOUND' || error.statusCode === 404);
    };

    // guacNotification ignores a new status while another is shown
    var show = function show(status) {
        guacNotification.showStatus(false);
        guacNotification.showStatus(status);
    };

    // Gives up on the held connection and leaves the client page
    var abandon = function abandon(managedClient) {
        guacNotification.showStatus(false);
        guacClientManager.removeManagedClient(managedClient.id);
        $location.url('/');
    };

    var enforce = function enforce(gate, identifier) {
        joinRequestService.enforce(identifier.dataSource, identifier.id)
            .then(gate.proceed, guacNotification.SHOW_REQUEST_ERROR);
    };

    var waitForAdmission;

    var outcome = function outcome(gate, identifier, textKey, canRetry) {
        var actions = [];
        if (canRetry)
            actions.push({ name : 'JOIN.ACTION_RETRY',
                callback : function retry() { waitForAdmission(gate, identifier); } });
        actions.push({ name : 'JOIN.ACTION_ENFORCE', className : 'danger',
            callback : function forced() { enforce(gate, identifier); } });
        actions.push({ name : 'JOIN.ACTION_CANCEL',
            callback : function cancel() { abandon(gate.managedClient); } });
        show({ title : 'JOIN.DIALOG_HEADER_WAITING', text : { key : textKey }, actions : actions });
    };

    waitForAdmission = function waitForAdmission(gate, identifier) {

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
                        callback : function forced() { stop(); enforce(gate, identifier); } },
                    { name : 'JOIN.ACTION_CANCEL',
                        callback : function cancelled() {
                            stop();
                            joinRequestService.cancelRequest(identifier.dataSource, created.id);
                            abandon(gate.managedClient);
                        } }
                ]
            });

            stopRouteWatch = $rootScope.$on('$routeChangeStart', function leaving() {
                stop();
                joinRequestService.cancelRequest(identifier.dataSource, created.id);
            });

            poll = $interval(function check() {
                joinRequestService.getRequest(identifier.dataSource, created.id)
                        .then(function polled(result) {
                    if (result.status === 'ADMITTED') {
                        stop();
                        guacNotification.showStatus(false);
                        guacClientManager.removeManagedClient(gate.managedClient.id);
                        var sharedId = ClientIdentifier.toString({
                            dataSource : identifier.dataSource + '-shared',
                            type       : ClientIdentifier.Types.CONNECTION,
                            id         : result.shareKey
                        });
                        // The client route does not reload between clients, so its
                        // ?key= handling never runs: add the key to the session first
                        authenticationService.updateCurrentToken({ key : result.shareKey })
                            .then(function keyAdded() {
                                $location.url('/client/' + sharedId);
                            }, guacNotification.SHOW_REQUEST_ERROR);
                    }
                    else if (result.status === 'DENIED') {
                        stop();
                        outcome(gate, identifier, 'JOIN.TEXT_DENIED', false);
                    }
                    else if (result.status === 'FREE' || result.status === 'CANCELLED') {
                        stop();
                        gate.proceed();
                    }
                }, function failed(error) {
                    stop();
                    if (isNotFound(error))
                        outcome(gate, identifier, 'JOIN.TEXT_EXPIRED', true);
                    else
                        gate.proceed();
                });
            }, POLL_MS);

        }, function notRequested() {
            // Free by now, or the mailbox is unavailable: never block connecting
            gate.proceed();
        });

    };

    return {

        /**
         * Connects the given client now, or once admitted or enforced if
         * another user holds the connection. Called for every display resize
         * until the client leaves the IDLE state.
         */
        connect : function connect(managedClient, width, height, originalConnect) {

            var identifier = ClientIdentifier.fromString(managedClient.id);
            if (managedClient.clientState.connectionState !== ManagedClientState.ConnectionState.IDLE
                    || identifier.type !== ClientIdentifier.Types.CONNECTION
                    || JDBC_DATA_SOURCES.indexOf(identifier.dataSource) === -1)
                return originalConnect(managedClient, width, height);

            var gate = managedClient.$$joinGate;
            if (gate) {
                gate.width = width;
                gate.height = height;
                if (gate.cleared)
                    return originalConnect(managedClient, width, height);
                return;
            }

            gate = managedClient.$$joinGate = {
                managedClient : managedClient,
                width         : width,
                height        : height,
                cleared       : false
            };

            gate.proceed = function proceed() {
                guacNotification.showStatus(false);
                gate.cleared = true;
                originalConnect(managedClient, gate.width, gate.height);
            };

            joinRequestService.getStatus(identifier.dataSource, identifier.id)
                    .then(function checked(status) {
                if (status.state === 'inUse')
                    waitForAdmission(gate, identifier);
                else
                    gate.proceed();
            }, function unchecked() {
                gate.proceed();
            });

        }

    };

}]);

angular.module('guacJdbcJoinRequests').run(['$injector', function joinRequests($injector) {

    var $interval          = $injector.get('$interval');
    var ClientIdentifier   = $injector.get('ClientIdentifier');
    var guacClientManager  = $injector.get('guacClientManager');
    var guacNotification   = $injector.get('guacNotification');
    var joinRequestService = $injector.get('joinRequestService');
    var ManagedClientState = $injector.get('ManagedClientState');

    var JDBC_DATA_SOURCES = ['postgresql', 'mysql', 'sqlserver'];
    var POLL_MS = 3000;

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
