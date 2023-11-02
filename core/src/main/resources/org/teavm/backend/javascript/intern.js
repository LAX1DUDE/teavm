/*
 *  Copyright 2019 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
"use strict";

let $rt_intern
if (teavm_javaMethodExists("java.lang.String", "intern()Ljava/lang/String;")) {
    $rt_intern = function() {
        let map = teavm_globals.Object.create(null);

        let get;
        if (typeof teavm_globals.WeakRef !== 'undefined') {
            let registry = new teavm_globals.FinalizationRegistry(value => {
                delete map[value];
            });

            get = str => {
                let key = $rt_ustr(str);
                let ref = map[key];
                let result = typeof ref !== 'undefined' ? ref.deref() : void 0;
                if (typeof result !== 'object') {
                    result = str;
                    map[key] = new teavm_globals.WeakRef(result);
                    registry.register(result, key);
                }
                return result;
            }
        } else {
            get = str => {
                let key = $rt_ustr(str);
                let result = map[key];
                if (typeof result !== 'object') {
                    result = str;
                    map[key] = result;
                }
                return result;
            }
        }

        return get;
    }();
} else {
    $rt_intern = str => str;
}