(ns clj-http.test.conn-mgr-test
  (:require [clj-http.conn-mgr :as conn-mgr]
            [clj-http.core :as core]
            [clj-http.test.core-test :refer [run-server]]
            [clojure.test :refer :all]
            [ring.adapter.jetty :as ring])
  (:import java.security.KeyStore
           [javax.net.ssl KeyManagerFactory TrustManagerFactory]
           org.apache.http.impl.conn.BasicHttpClientConnectionManager))

(def client-ks "test-resources/client-keystore")
(def client-ks-pass "keykey")
(def secure-request {:request-method :get :uri "/"
                     :server-port 18084 :scheme :https
                     :keystore client-ks :keystore-pass client-ks-pass
                     :trust-store client-ks :trust-store-pass client-ks-pass
                     :server-name "localhost" :insecure? true})

(defn secure-handler [req]
  (if (nil? (:ssl-client-cert req))
    {:status 403}
    {:status 200}))

(deftest load-keystore
  (let [ks (conn-mgr/get-keystore "test-resources/keystore" nil "keykey")]
    (is (instance? KeyStore ks))
    (is (> (.size ks) 0))))

(deftest use-existing-keystore
  (let [ks (conn-mgr/get-keystore "test-resources/keystore" nil "keykey")
        ks (conn-mgr/get-keystore ks)]
    (is (instance? KeyStore ks))
    (is (> (.size ks) 0))))

(deftest load-keystore-with-nil-pass
  (let [ks (conn-mgr/get-keystore "test-resources/keystore" nil nil)]
    (is (instance? KeyStore ks))))

(def array-of-trust-manager
  (let [ks (conn-mgr/get-keystore "test-resources/keystore" nil "keykey")
        tmf (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
              (.init ks))]
    (.getTrustManagers tmf)))

(def array-of-key-manager
  (let [ks (conn-mgr/get-keystore "test-resources/keystore" nil "keykey")
        tmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
              (.init ks (.toCharArray "keykey")))]
    (.getKeyManagers tmf)))

(deftest ^:integration ssl-client-cert-get
  (let [server (ring/run-jetty secure-handler
                               {:port 18083 :ssl-port 18084
                                :ssl? true
                                :join? false
                                :keystore "test-resources/keystore"
                                :key-password "keykey"
                                :client-auth :want})]
    (try
      (let [resp (core/request {:request-method :get :uri "/get"
                                :server-port 18084 :scheme :https
                                :insecure? true :server-name "localhost"})]
        (is (= 403 (:status resp))))
      (let [resp (core/request secure-request)]
        (is (= 200 (:status resp))))
      (finally
        (.stop server)))))

(deftest ^:integration ssl-client-cert-get-async
  (let [server (ring/run-jetty secure-handler
                               {:port 18083 :ssl-port 18084
                                :ssl? true
                                :join? false
                                :keystore "test-resources/keystore"
                                :key-password "keykey"
                                :client-auth :want})]
    (try
      (let [resp (promise)
            exception (promise)
            _ (core/request {:request-method :get :uri "/get"
                             :server-port 18084 :scheme :https
                             :insecure? true :server-name "localhost"
                             :async? true} resp exception)]
        (is (= 403 (:status (deref resp 1000 {:status :timeout})))))
      (let [resp (promise)
            exception (promise)
            _ (core/request (assoc secure-request :async? true) resp exception)]
        (is (= 200 (:status (deref resp 1000 {:status :timeout})))))

      (testing "with reusable connection pool"
        (let [pool (conn-mgr/make-reusable-async-conn-manager {:timeout 10000
                                                               :keystore client-ks :keystore-pass client-ks-pass
                                                               :trust-store client-ks :trust-store-pass client-ks-pass
                                                               :insecure? true})]
          (try
            (let [resp (promise) exception (promise)
                  _ (core/request {:request-method :get :uri "/get"
                                   :server-port 18084 :scheme :https
                                   :server-name "localhost"
                                   :connection-manager pool :async? true} resp exception)]
              (is (= 200 (:status (deref resp 1000 {:status :timeout}))))
              (is (:body @resp))
              (is (not (realized? exception))))
            (finally
              (conn-mgr/shutdown-manager pool)))))
      (finally
        (.stop server)))))

(deftest ^:integration t-closed-conn-mgr-for-as-stream
  (run-server)
  (let [shutdown? (atom false)
        cm (proxy [BasicHttpClientConnectionManager] []
             (shutdown []
               (reset! shutdown? true)))]
    (try
      (core/request {:request-method :get :uri "/timeout"
                     :server-port 18080 :scheme :http
                     :server-name "localhost"
                     ;; timeouts forces an exception being thrown
                     :socket-timeout 1
                     :connection-timeout 1
                     :connection-manager cm
                     :as :stream})
      (is false "request should have thrown an exception")
      (catch Exception e))
    (is @shutdown? "Connection manager has been shutdown")))

(deftest ^:integration t-closed-conn-mgr-for-empty-body
  (run-server)
  (let [shutdown? (atom false)
        cm (proxy [BasicHttpClientConnectionManager] []
             (shutdown []
               (reset! shutdown? true)))
        response (core/request {:request-method :get :uri "/unmodified-resource"
                                :server-port 18080 :scheme :http
                                :server-name "localhost"
                                :connection-manager cm})]
    (is (nil? (:body response)) "response shouldn't have body")
    (is (= 304 (:status response)))
    (is @shutdown? "connection manager should be shutdown")))

(deftest t-reusable-conn-mgrs
  (let [regular (conn-mgr/make-regular-conn-manager {})
        regular-reusable (conn-mgr/make-reusable-conn-manager {})
        async (conn-mgr/make-regular-async-conn-manager {})
        async-reusable (conn-mgr/make-reusable-async-conn-manager {})
        async-reuseable (conn-mgr/make-reuseable-async-conn-manager {})]
    (is (false? (conn-mgr/reusable? regular)))
    (is (true? (conn-mgr/reusable? regular-reusable)))
    (is (false? (conn-mgr/reusable? async)))
    (is (true? (conn-mgr/reusable? async-reusable)))
    (is (true? (conn-mgr/reusable? async-reuseable)))))
