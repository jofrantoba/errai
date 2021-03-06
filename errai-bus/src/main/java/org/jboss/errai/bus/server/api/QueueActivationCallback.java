/*
 * Copyright (C) 2012 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.errai.bus.server.api;

/**
 * This interface, <tt>QueueActivationCallback</tt>, is a template for creating a callback function for a queue
 */
public interface QueueActivationCallback {

  /**
   * This function is responsible for activating a queue. It starts the message transmission
   *
   * @param queue - the message queue to be activated
   */
  public void activate(MessageQueue queue);
}
